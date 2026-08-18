package com.mio.ai.qa;

import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.llm.LlmPricingProperties.ModelPrice;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 셀 벤치마크가 쓰는 단가표.
 *
 * <p>단가를 코드에 다시 적지 않는다. 프로덕션이 실제 비용을 계산할 때 읽는
 * {@code application.yml} 의 {@code openai.pricing} 을 그대로 읽고, 실행 직전에 핀한 후보의
 * 단가만 그 위에 얹는다(로드맵 §11.3 "실제 후보 ID 와 당시 단가는 실행 직전에 registry 에
 * 핀한다"). 두 벌의 단가표가 생기면 "비용" 이라는 말이 두 가지를 뜻하게 된다.
 *
 * <p>등록되지 않은 모델의 비용은 <b>0 이 아니라 없음</b>이다({@link Optional#empty()}).
 * {@code LlmCostCalculator} 가 {@code null} 을 돌려주고 {@code mio.llm.cost.unpriced} 를
 * 올리는 것과 같은 규칙이며, 리포트에는 {@code 미상} 으로 찍힌다. 0 으로 접으면 단가 등록을
 * 잊은 상위 모델이 "공짜 모델" 로 집계돼 셀 비교가 통째로 뒤집힌다.
 */
final class CellPricingBook {

    /** 단가 단위. OpenAI 가격표와 같다. */
    private static final BigDecimal TOKENS_PER_PRICE_UNIT = new BigDecimal("1000000");

    static final String SOURCE = "application.yml openai.pricing";

    private final Map<String, ModelPrice> prices;
    private final Map<String, String> origins;
    private final String pricingAsOf;

    private CellPricingBook(Map<String, ModelPrice> prices, Map<String, String> origins,
                            String pricingAsOf) {
        this.prices = Map.copyOf(prices);
        this.origins = Map.copyOf(origins);
        this.pricingAsOf = pricingAsOf;
    }

    /**
     * 설정 단가 + 실행 직전 핀한 후보 단가.
     *
     * @param overrides   후보 모델의 단가. 설정에 없는 모델을 여기서 채운다
     * @param pricingAsOf 단가 기준일. 없으면 {@link EvalRunManifest#PRICING_DATE_UNRECORDED}
     */
    static CellPricingBook load(Map<String, ModelPrice> overrides, String pricingAsOf) {
        Map<String, ModelPrice> merged = new LinkedHashMap<>();
        Map<String, String> origins = new LinkedHashMap<>();
        fromApplicationYaml().forEach((model, price) -> {
            merged.put(model, price);
            origins.put(model, SOURCE);
        });
        if (overrides != null) {
            overrides.forEach((model, price) -> {
                merged.put(model, price);
                origins.put(model, "실행 직전 registry 핀");
            });
        }
        return new CellPricingBook(merged, origins,
                pricingAsOf == null || pricingAsOf.isBlank()
                        ? EvalRunManifest.PRICING_DATE_UNRECORDED
                        : pricingAsOf);
    }

    /**
     * {@code openai.pricing.models} 를 설정 파일에서 읽는다.
     *
     * <p>Spring 컨텍스트를 띄우지 않는다 — 비용 추정 테스트는 DB·API 키 없이 아무나 돌릴 수
     * 있어야 한다는 것이 요구사항이고, 컨텍스트를 요구하면 그 자리에서 깨진다.
     */
    private static Map<String, ModelPrice> fromApplicationYaml() {
        Resource resource = new ClassPathResource("application.yml");
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "application.yml 을 찾지 못했다 — 단가표 없이 비용을 추정하면 그 숫자는 근거가 없다");
        }
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load("application.yml", resource);
            MutablePropertySources propertySources = new MutablePropertySources();
            sources.forEach(propertySources::addLast);
            Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
            LlmPricingProperties bound = binder
                    .bind("openai.pricing", Bindable.of(LlmPricingProperties.class))
                    .orElseGet(LlmPricingProperties::new);
            return bound.getModels();
        } catch (IOException e) {
            throw new UncheckedIOException("application.yml 단가 블록을 읽지 못했다", e);
        }
    }

    /** {@code LlmCostCalculator} 에 그대로 넣을 수 있는 형태. 실행 중 실제 비용도 이 값으로 계산된다. */
    LlmPricingProperties asProperties() {
        LlmPricingProperties properties = new LlmPricingProperties();
        properties.setModels(prices);
        return properties;
    }

    boolean isPriced(String model) {
        ModelPrice price = prices.get(model);
        return price != null && price.isValid();
    }

    String originOf(String model) {
        return origins.getOrDefault(model, "미등록");
    }

    String pricingAsOf() {
        return pricingAsOf;
    }

    /** 단가가 등록된 모델 목록. 리포트가 "무엇을 근거로 계산했나" 를 같이 싣게 한다. */
    Map<String, ModelPrice> prices() {
        return new TreeMap<>(prices);
    }

    /**
     * 토큰 수를 USD 로 환산한다.
     *
     * @return 단가 미등록이면 {@link Optional#empty()} — 0 이 아니다
     */
    Optional<BigDecimal> costUsd(String model, long promptTokens, long completionTokens,
                                 long cachedTokens) {
        ModelPrice price = prices.get(model);
        if (price == null || !price.isValid()) {
            return Optional.empty();
        }
        long cached = Math.max(0, Math.min(cachedTokens, promptTokens));
        long uncached = promptTokens - cached;
        BigDecimal cachedUnit = price.cachedInput() != null ? price.cachedInput() : price.input();
        BigDecimal total = price.input().multiply(BigDecimal.valueOf(uncached))
                .add(cachedUnit.multiply(BigDecimal.valueOf(cached)))
                .add(price.output().multiply(BigDecimal.valueOf(completionTokens)))
                .divide(TOKENS_PER_PRICE_UNIT, java.math.MathContext.DECIMAL64);
        return Optional.of(total);
    }

    /** 리포트·아카이브에 쓰는 표기. 모르면 0 이 아니라 "미상" 이다. */
    static String format(Optional<BigDecimal> usd) {
        return usd.map(v -> "$" + v.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString())
                .orElse("미상");
    }
}
