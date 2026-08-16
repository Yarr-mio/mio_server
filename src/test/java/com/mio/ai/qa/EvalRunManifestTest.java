package com.mio.ai.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실행 manifest 가 출처 누락을 조용히 통과시키지 않는지 검증한다 (로드맵 §10.5 / P0-8).
 *
 * <p>A~E 셀 비교는 "같은 split 을 같은 코드·프롬프트·정책 버전으로 돌렸다" 는 전제에서만
 * 성립한다. 그 전제를 확인할 수 없는 실행으로 Go/No-Go 를 내리면, 근거가 없다는 사실이
 * 근거가 있다는 외양에 가려진다. 그래서 누락은 경고가 아니라 실패여야 한다.
 */
class EvalRunManifestTest {

    @Test
    @DisplayName("필수 출처 항목이 비면 기록을 만들지 못한다")
    void blankProvenanceFieldsAreRejected() {
        assertThatThrownBy(() -> manifestWith(m -> m.promptVersion = "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt_version");
        assertThatThrownBy(() -> manifestWith(m -> m.policyVersion = null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy_version");
        assertThatThrownBy(() -> manifestWith(m -> m.randomSeed = null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("random_seed");
        assertThatThrownBy(() -> manifestWith(m -> m.datasetSplit = ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset_split");
        assertThatThrownBy(() -> manifestWith(m -> m.pricingAsOf = null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pricing_as_of");
    }

    @Test
    @DisplayName("어떤 모델이 낸 수치인지 모르는 실행은 기록하지 않는다")
    void missingModelsAreRejected() {
        assertThatThrownBy(() -> manifestWith(m -> m.models = Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("models");
        assertThatThrownBy(() -> manifestWith(m -> m.models = mapWithNullValue("judge")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("models.judge");
    }

    /**
     * 값만 검사하면 역할 이름이 빈 항목이 {@code "model."} 이라는 이름 없는 행으로 기록된다.
     * 어떤 역할이 그 모델을 썼는지 모르는 행은 셀 비교에 쓸 수 없다.
     */
    @Test
    @DisplayName("모델 역할 이름이 비어도 기록하지 않는다")
    void blankModelRoleIsRejected() {
        assertThatThrownBy(() -> manifestWith(m -> m.models = Map.of(" ", "gpt-4o-mini")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("models");
        assertThatThrownBy(() -> manifestWith(m -> m.models = mapWithNullKey("gpt-4o-mini")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("models");
    }

    /**
     * 생성자가 검증한 값이 {@code extra} 의 동명 키로 덮이면, 검증을 통과한 manifest 와 디스크에
     * 남은 기록이 서로 다른 값을 말하게 된다. 그 순간 "출처를 강제한다" 는 전제가 무너지므로
     * 기록 시점이 아니라 생성 시점에 막는다 — 어긋난 manifest 는 아예 만들어지지 않아야 한다.
     */
    @Test
    @DisplayName("extra 는 manifest 가 직접 기록하는 항목을 덮어쓸 수 없다")
    void extraCannotShadowValidatedFields() {
        assertThatThrownBy(() -> manifestWith(m -> m.extra = Map.of("prompt_version", "v3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt_version");
        assertThatThrownBy(() -> manifestWith(m -> m.extra = Map.of("command", "./gradlew 다른것")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
        assertThatThrownBy(() -> manifestWith(m -> m.extra = Map.of("dataset_split", "locked_gold")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset_split");
    }

    @Test
    @DisplayName("extra 는 gate_·model. 네임스페이스도 침범할 수 없다")
    void extraCannotShadowNamespacedFields() {
        assertThatThrownBy(() -> manifestWith(
                m -> m.extra = Map.of("gate_final_false_negative", "<= 999건")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gate_final_false_negative");
        assertThatThrownBy(() -> manifestWith(m -> m.extra = Map.of("model.input_judge", "gpt-4o")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model.input_judge");
    }

    @Test
    @DisplayName("예약 키와 겹치지 않는 부가 정보는 그대로 기록에 실린다")
    void nonCollidingExtraIsRecorded() {
        Map<String, String> metadata = manifestWith(
                m -> m.extra = Map.of("judge_calls", "112", "elapsed", "8m 12s")).toMetadata();

        assertThat(metadata)
                .containsEntry("judge_calls", "112")
                .containsEntry("elapsed", "8m 12s")
                // 덮어쓰기 방지가 정상 값까지 잡아먹지 않는지 함께 본다.
                .containsEntry("prompt_version", EvalRunManifest.UNVERSIONED);
    }

    @Test
    @DisplayName("빈 평가셋의 실행 기록은 남기지 않는다")
    void emptyDatasetIsRejected() {
        assertThatThrownBy(() -> manifestWith(m -> m.datasetSize = 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset_size");
    }

    /**
     * 미구현 항목은 생략이 아니라 명시적 표식으로 남는다. 행이 사라지면 나중에 "없었다" 와
     * "안 남겼다" 를 구별할 수 없다.
     */
    @Test
    @DisplayName("프롬프트 버전 체계가 없어도 항목 자체는 기록에 남는다")
    void unimplementedProvenanceStillAppearsInTheRecord() {
        Map<String, String> metadata = manifestWith(m -> { }).toMetadata();

        assertThat(metadata).containsEntry("prompt_version", EvalRunManifest.UNVERSIONED);
        assertThat(metadata.get("prompt_version")).contains("미구현");
    }

    @Test
    @DisplayName("여러 실행을 나란히 읽을 수 있게 항목 순서를 고정한다")
    void metadataOrderIsStable() {
        Map<String, String> metadata = manifestWith(m -> m.models = new LinkedHashMap<>(Map.of(
                "output_judge", "gpt-4o-mini",
                "conversation", "gpt-4o"))).toMetadata();

        List<String> keys = List.copyOf(metadata.keySet());
        assertThat(keys).startsWith(
                "scope", "cell", "dataset", "dataset_split", "dataset_size", "label_guide");
        // 역할이 늘어도 같은 자리에 오도록 정렬해 싣는다.
        assertThat(keys.indexOf("model.conversation")).isLessThan(keys.indexOf("model.output_judge"));
        // 재현 명령은 항상 마지막 — 사람이 복사해 가는 값이다.
        assertThat(keys.get(keys.size() - 1)).isEqualTo("command");
    }

    @Test
    @DisplayName("게이트 값은 gate_ 접두사로 출처와 구분해 싣는다")
    void gatesAreNamespaced() {
        Map<String, String> metadata = manifestWith(
                m -> m.gates = Map.of("final_false_negative", "<= 5건")).toMetadata();

        assertThat(metadata).containsEntry("gate_final_false_negative", "<= 5건");
    }

    @Test
    @DisplayName("만든 뒤 호출부가 맵을 바꿔도 기록된 manifest 는 변하지 않는다")
    void manifestDoesNotAliasCallerMaps() {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("conversation", "gpt-4o");
        EvalRunManifest manifest = manifestWith(m -> m.models = models);

        models.put("conversation", "somethingelse");

        assertThat(manifest.toMetadata()).containsEntry("model.conversation", "gpt-4o");
    }

    // ── helpers ──────────────────────────────────────────────────

    /** 유효한 manifest 를 만든 뒤 한 항목만 흠집 내서 그 항목의 검증만 시험한다. */
    private EvalRunManifest manifestWith(java.util.function.Consumer<Draft> mutation) {
        Draft draft = new Draft();
        mutation.accept(draft);
        return new EvalRunManifest(
                draft.scope, draft.cell, draft.datasetVersion, draft.datasetSplit, draft.datasetSize,
                draft.labelGuide, draft.models, draft.promptVersion, draft.policyVersion,
                draft.pricingAsOf, draft.randomSeed, draft.command, draft.gates, draft.extra);
    }

    private Map<String, String> mapWithNullValue(String key) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, null);
        return map;
    }

    private Map<String, String> mapWithNullKey(String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(null, value);
        return map;
    }

    private static final class Draft {
        String scope = "full path";
        String cell = EvalRunManifest.BASELINE_CELL;
        String datasetVersion = "crisis-corpus-v1";
        String datasetSplit = "dev_gold";
        int datasetSize = 172;
        String labelGuide = "docs/eval/crisis-corpus-labeling-guide.md";
        Map<String, String> models = Map.of("input_judge", "gpt-4o-mini");
        String promptVersion = EvalRunManifest.UNVERSIONED;
        String policyVersion = "v2.0-phase2";
        String pricingAsOf = EvalRunManifest.PRICING_DATE_UNRECORDED;
        String randomSeed = EvalRunManifest.NO_SEED;
        String command = "./gradlew test";
        Map<String, String> gates = Map.of();
        Map<String, String> extra = Map.of();
    }
}
