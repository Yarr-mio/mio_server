package com.mio.ai.llm;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 역할→모델 해석기 (#479). 모델 변경을 코드 수정이 아니라 설정으로 만든다.
 *
 * <p>해석은 기동 시점에 한 번 확정되고, 두 검증을 fail-closed 로 통과해야 한다.
 *
 * <ol>
 *   <li><b>allowlist</b> — 해석된 모델이 {@code mio.ai.models.allowed} 밖이면 기동 실패.
 *       allowlist 가 비어 있으면 {@link ModelRole} 기본 모델 집합만 허용된다 —
 *       "설정을 안 했다"가 "전부 허용"이 되면 목록의 의미가 없다.</li>
 *   <li><b>단가</b> — 해석된 모델이 {@code openai.pricing.models} 에 없으면 기동 실패.
 *       단가 미등록 모델은 비용이 0 이 아니라 '미상'으로 쌓이는데, 그 사실을 지표로
 *       사후에 아는 것과 기동에서 막는 것은 다른 일이다.</li>
 * </ol>
 *
 * <p>토큰 상한과 프롬프트는 여기서 다루지 않는다 — 상한은 프롬프트 길이 지시와 같은 파일에
 * 있어야 함께 바뀐다(호출부 상수). 이 카탈로그는 모델 ID 만 해석한다.
 */
@Component
public class ModelCatalog {

    private final Map<ModelRole, String> models;

    public ModelCatalog(ModelCatalogProperties properties, LlmPricingProperties pricing) {
        Map<ModelRole, String> resolved = new EnumMap<>(ModelRole.class);
        for (ModelRole role : ModelRole.values()) {
            resolved.put(role, role.defaultModel());
        }
        for (Map.Entry<String, String> entry : properties.getRoles().entrySet()) {
            resolved.put(ModelRole.fromConfigKey(entry.getKey()), entry.getValue());
        }
        requireAllowed(resolved, effectiveAllowlist(properties));
        requirePriced(resolved, pricing);
        this.models = Map.copyOf(resolved);
    }

    public String modelFor(ModelRole role) {
        return models.get(role);
    }

    /** 설정 없는 기본 카탈로그. 테스트가 프로덕션과 같은 기본 해석을 쓸 때 사용한다. */
    public static ModelCatalog defaults() {
        LlmPricingProperties pricing = new LlmPricingProperties();
        Map<String, LlmPricingProperties.ModelPrice> table = new LinkedHashMap<>();
        for (ModelRole role : ModelRole.values()) {
            table.put(role.defaultModel(), new LlmPricingProperties.ModelPrice(
                    BigDecimal.ONE, null, BigDecimal.ONE));
        }
        pricing.setModels(table);
        return new ModelCatalog(new ModelCatalogProperties(), pricing);
    }

    private static Set<String> effectiveAllowlist(ModelCatalogProperties properties) {
        if (!properties.getAllowed().isEmpty()) {
            return Set.copyOf(properties.getAllowed());
        }
        return Arrays.stream(ModelRole.values())
                .map(ModelRole::defaultModel)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireAllowed(Map<ModelRole, String> resolved, Set<String> allowed) {
        for (Map.Entry<ModelRole, String> entry : resolved.entrySet()) {
            if (!allowed.contains(entry.getValue())) {
                throw new IllegalStateException(
                        "역할 %s 가 allowlist 밖의 모델 '%s' 로 해석됐다. mio.ai.models.allowed 에 "
                                .formatted(entry.getKey().configKey(), entry.getValue())
                                + "등재해야 하며, 등재는 PR 로만 한다 — 안전 게이트를 우회하지 않기 위해서다");
            }
        }
    }

    private static void requirePriced(Map<ModelRole, String> resolved, LlmPricingProperties pricing) {
        Map<String, LlmPricingProperties.ModelPrice> table = pricing.getModels();
        for (Map.Entry<ModelRole, String> entry : resolved.entrySet()) {
            LlmPricingProperties.ModelPrice price = table.get(entry.getValue());
            if (price == null || !price.isValid()) {
                throw new IllegalStateException(
                        "역할 %s 의 모델 '%s' 가 openai.pricing.models 에 없다. 단가 없이 운영하면 "
                                .formatted(entry.getKey().configKey(), entry.getValue())
                                + "비용이 '미상'으로 쌓인다 — 단가를 등록해야 기동한다");
            }
        }
    }
}
