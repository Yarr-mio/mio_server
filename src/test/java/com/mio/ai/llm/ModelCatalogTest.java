package com.mio.ai.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 역할→모델 해석과 fail-closed 검증 (#479).
 *
 * <p>이 카탈로그의 존재 이유는 두 가지다. 모델 변경이 코드 수정이 아니라 설정이 되게 하는 것,
 * 그리고 그 설정이 <b>allowlist·단가표 밖으로는 절대 나가지 못하게</b> 기동 시점에 막는 것.
 * 검증이 런타임으로 미뤄지면 미등록 모델의 비용이 '미상'으로 쌓이는 사고를 사후에야 알게 된다.
 */
class ModelCatalogTest {

    @Test
    @DisplayName("설정이 없으면 현행 상수와 같은 모델로 해석된다")
    void defaultsMatchCurrentConstants() {
        ModelCatalog catalog = ModelCatalog.defaults();

        assertThat(catalog.modelFor(ModelRole.GENERATION)).isEqualTo("gpt-4o");
        assertThat(catalog.modelFor(ModelRole.INPUT_JUDGE)).isEqualTo("gpt-4o-mini");
        assertThat(catalog.modelFor(ModelRole.OUTPUT_JUDGE)).isEqualTo("gpt-4o-mini");
        assertThat(catalog.modelFor(ModelRole.CBT_CLASSIFIER)).isEqualTo("gpt-4o-mini");
    }

    @Test
    @DisplayName("역할 설정이 기본값을 덮어쓴다 — allowlist 등재 + 단가 등록이 전제")
    void roleOverrideChangesResolution() {
        ModelCatalog catalog = new ModelCatalog(
                properties(Map.of("generation", "gpt-4.1-nano"),
                        List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1-nano")),
                pricing("gpt-4o", "gpt-4o-mini", "gpt-4.1-nano"));

        assertThat(catalog.modelFor(ModelRole.GENERATION)).isEqualTo("gpt-4.1-nano");
        assertThat(catalog.modelFor(ModelRole.INPUT_JUDGE))
                .as("덮어쓰지 않은 역할은 기본값을 유지한다")
                .isEqualTo("gpt-4o-mini");
    }

    @Test
    @DisplayName("yml kebab-case 키가 역할로 해석된다")
    void kebabCaseKeysResolveToRoles() {
        ModelCatalog catalog = new ModelCatalog(
                properties(Map.of("cbt-classifier", "gpt-4o"),
                        List.of("gpt-4o", "gpt-4o-mini")),
                pricing("gpt-4o", "gpt-4o-mini"));

        assertThat(catalog.modelFor(ModelRole.CBT_CLASSIFIER)).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("환경 변수 relaxed binding 이 만드는 점 표기 키도 역할로 해석된다")
    void envVarDottedKeysResolveToRoles() {
        // MIO_AI_MODELS_ROLES_INPUT_JUDGE 는 Spring 이 맵 키 'input.judge' 로 바인딩한다.
        // 운영 배포 수단이 환경 변수(.env·docker-compose)이므로 이 표기가 막히면
        // canary 롤백 중 기동 실패로 이어진다.
        ModelCatalog catalog = new ModelCatalog(
                properties(Map.of("input.judge", "gpt-4o"),
                        List.of("gpt-4o", "gpt-4o-mini")),
                pricing("gpt-4o", "gpt-4o-mini"));

        assertThat(catalog.modelFor(ModelRole.INPUT_JUDGE)).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("모르는 역할 키는 기동을 실패시킨다 — 오타가 조용히 무시되면 안 된다")
    void unknownRoleKeyFailsStartup() {
        assertThatThrownBy(() -> new ModelCatalog(
                properties(Map.of("generaton", "gpt-4o"), List.of("gpt-4o", "gpt-4o-mini")),
                pricing("gpt-4o", "gpt-4o-mini")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generaton")
                .hasMessageContaining("generation");
    }

    @Test
    @DisplayName("allowlist 에 없는 모델로의 해석은 기동을 실패시킨다")
    void modelOutsideAllowlistFailsStartup() {
        assertThatThrownBy(() -> new ModelCatalog(
                properties(Map.of("generation", "gpt-4.1-nano"),
                        List.of("gpt-4o", "gpt-4o-mini")),
                pricing("gpt-4o", "gpt-4o-mini", "gpt-4.1-nano")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-4.1-nano")
                .hasMessageContaining("allowed");
    }

    @Test
    @DisplayName("allowlist 를 비워두면 기본 모델 집합만 허용된다 — 비움이 전부 허용이 되면 안 된다")
    void emptyAllowlistMeansDefaultsOnly() {
        assertThatThrownBy(() -> new ModelCatalog(
                properties(Map.of("generation", "gpt-4.1-nano"), List.of()),
                pricing("gpt-4o", "gpt-4o-mini", "gpt-4.1-nano")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-4.1-nano");
    }

    @Test
    @DisplayName("단가 미등록 모델로의 해석은 기동을 실패시킨다 — 비용 '미상' 사고를 기동에서 막는다")
    void unpricedModelFailsStartup() {
        assertThatThrownBy(() -> new ModelCatalog(
                properties(Map.of("generation", "gpt-4.1-nano"),
                        List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1-nano")),
                pricing("gpt-4o", "gpt-4o-mini")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-4.1-nano")
                .hasMessageContaining("openai.pricing.models");
    }

    @Test
    @DisplayName("기본값 자체도 단가 검증을 통과해야 한다 — 기본값이라고 면제되면 검증이 반쪽이다")
    void defaultsAreValidatedAgainstPricingToo() {
        assertThatThrownBy(() -> new ModelCatalog(
                properties(Map.of(), List.of("gpt-4o", "gpt-4o-mini")),
                pricing("gpt-4o")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-4o-mini");
    }

    // ── 픽스처 ─────────────────────────────────────────────────────

    private static ModelCatalogProperties properties(Map<String, String> roles,
                                                     List<String> allowed) {
        ModelCatalogProperties props = new ModelCatalogProperties();
        props.setRoles(roles);
        props.setAllowed(allowed);
        return props;
    }

    private static LlmPricingProperties pricing(String... models) {
        LlmPricingProperties props = new LlmPricingProperties();
        java.util.Map<String, LlmPricingProperties.ModelPrice> table = new java.util.LinkedHashMap<>();
        for (String model : models) {
            table.put(model, new LlmPricingProperties.ModelPrice(
                    BigDecimal.ONE, null, BigDecimal.TEN));
        }
        props.setModels(table);
        return props;
    }
}
