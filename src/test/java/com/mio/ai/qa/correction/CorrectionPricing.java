package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmPricingProperties;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 모델 단가를 프로덕션 설정({@code application.yml} 의 {@code openai.pricing.models}) 에서 그대로
 * 읽는다 (이슈 #552). 견적과 실측 비용이 운영과 같은 단가를 쓰게 하려는 것이고, 단가를 코드에
 * 복제하면 운영 단가가 바뀔 때 조용히 어긋난다.
 */
final class CorrectionPricing {

    private CorrectionPricing() {}

    static LlmPricingProperties load() {
        try (InputStream in = CorrectionPricing.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new IllegalStateException("application.yml 을 클래스패스에서 찾지 못했다");
            }
            for (Object document : new Yaml().loadAll(in)) {
                Map<String, LlmPricingProperties.ModelPrice> models = pricingModels(document);
                if (!models.isEmpty()) {
                    LlmPricingProperties pricing = new LlmPricingProperties();
                    pricing.setModels(models);
                    return pricing;
                }
            }
            throw new IllegalStateException("application.yml 에 openai.pricing.models 가 없다");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, LlmPricingProperties.ModelPrice> pricingModels(Object document) {
        Map<String, LlmPricingProperties.ModelPrice> result = new LinkedHashMap<>();
        if (!(document instanceof Map<?, ?> root)) {
            return result;
        }
        Object openai = root.get("openai");
        if (!(openai instanceof Map<?, ?> openaiMap) || !(openaiMap.get("pricing") instanceof Map<?, ?> pricing)
                || !(pricing.get("models") instanceof Map<?, ?> models)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) models).entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> price)) {
                continue;
            }
            // Spring 의 relaxed binding 때문에 키에 점·하이픈이 있는 모델명은 "[gpt-4o]" 로 적는다.
            String name = String.valueOf(entry.getKey()).replaceAll("^\\[|]$", "");
            result.put(name, new LlmPricingProperties.ModelPrice(
                    decimal(price.get("input")), decimal(price.get("cached-input")), decimal(price.get("output"))));
        }
        return result;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }
}
