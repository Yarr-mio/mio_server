package com.mio.ai.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 모델 registry 계약 (이슈 #454, 로드맵 §10.3·§11.3).
 *
 * <p>여기서 잠그는 것은 두 가지다. 상위 모델 후보를 핀하지 않은 실행은 <b>시작되지 않는다</b>는
 * 것과, 운영 모델 기본값이 프로덕션 상수와 <b>어긋난 채 남지 않는다</b>는 것이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] A~E 셀 모델 registry")
class CellModelRegistryTest {

    /** 프로덕션 모델 상수의 위치. 상수가 private 이라 소스에서 읽는다. */
    private static final Map<String, String> PRODUCTION_CONSTANTS = Map.of(
            "src/main/java/com/mio/ai/orchestrator/ConversationOrchestrator.java", "LLM_MODEL",
            "src/main/java/com/mio/ai/judge/InputJudge.java", "JUDGE_MODEL",
            "src/main/java/com/mio/ai/judge/OutputJudge.java", "JUDGE_MODEL");

    @Test
    @DisplayName("상위 모델을 핀하지 않으면 셀 B·D·E 실행이 막히고, 메시지가 핀 방법을 알려준다")
    void frontierCellsFailClosedWithoutPin() {
        for (BenchmarkCell cell : new BenchmarkCell[]{BenchmarkCell.B, BenchmarkCell.C,
                BenchmarkCell.D, BenchmarkCell.E}) {
            assertThatThrownBy(() -> CellModelRegistry.resolve(cell, Map.of()))
                    .as("셀 %s 는 상위 모델 후보 없이 실행될 수 없어야 한다", cell)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CellModelRegistry.MODEL_PROPERTY_PREFIX)
                    .hasMessageContaining("registry 에 핀");
        }
    }

    @Test
    @DisplayName("셀 A 는 운영 기본값만으로 해석된다 — 기준선은 핀 없이도 돌아야 한다")
    void baselineResolvesWithProductionDefaults() {
        CellModelRegistry registry = CellModelRegistry.resolve(BenchmarkCell.A, Map.of());

        assertThat(registry.manifestModels())
                .containsKeys("generation", "input_safety", "output_judge");
        assertThat(registry.pinSources().values()).allMatch("프로덕션 기본값"::equals);
        assertThat(registry.unpricedOnlineModels())
                .as("기준선 모델은 application.yml 에 단가가 등록돼 있어야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("운영 기본값이 프로덕션 상수와 같다 — 프로덕션이 모델을 바꾸면 여기서 깨진다")
    void operationalDefaultsTrackProductionConstants() {
        CellModelRegistry registry = CellModelRegistry.resolve(BenchmarkCell.A, Map.of());
        Path root = LockedEvalContaminationScanner.findRepoRoot();

        assertThat(registry.modelFor(CellModelRole.GENERATION)).isEqualTo(constant(root,
                "src/main/java/com/mio/ai/orchestrator/ConversationOrchestrator.java", "LLM_MODEL"));
        assertThat(registry.modelFor(CellModelRole.INPUT_SAFETY)).isEqualTo(constant(root,
                "src/main/java/com/mio/ai/judge/InputJudge.java", "JUDGE_MODEL"));
        assertThat(registry.modelFor(CellModelRole.OUTPUT_JUDGE)).isEqualTo(constant(root,
                "src/main/java/com/mio/ai/judge/OutputJudge.java", "JUDGE_MODEL"));
        assertThat(PRODUCTION_CONSTANTS).hasSize(3);
    }

    @Test
    @DisplayName("핀한 모델과 단가가 registry 를 통해 셀에 그대로 전달된다")
    void pinnedModelAndPriceFlowThrough() {
        CellModelRegistry registry = CellModelRegistry.resolve(BenchmarkCell.B, Map.of(
                CellModelRegistry.MODEL_PROPERTY_PREFIX + "generation", "candidate-x",
                CellModelRegistry.PRICE_PROPERTY_PREFIX + "candidate-x", "5.0/2.5/20.0",
                CellModelRegistry.PRICING_AS_OF_PROPERTY, "2026-08-16"));

        assertThat(registry.modelFor(CellModelRole.GENERATION)).isEqualTo("candidate-x");
        assertThat(registry.componentToModel()).containsEntry("MAIN_GENERATION", "candidate-x");
        assertThat(registry.pricing().isPriced("candidate-x")).isTrue();
        assertThat(registry.pricing().pricingAsOf()).isEqualTo("2026-08-16");
        assertThat(registry.unpricedOnlineModels()).isEmpty();
    }

    @Test
    @DisplayName("단가를 핀하지 않은 후보는 비용이 0 이 아니라 미상으로 남는다")
    void unpricedCandidateStaysUnknown() {
        CellModelRegistry registry = CellModelRegistry.resolve(BenchmarkCell.B, Map.of(
                CellModelRegistry.MODEL_PROPERTY_PREFIX + "generation", "candidate-y"));

        assertThat(registry.unpricedOnlineModels()).containsExactly("candidate-y");
        assertThat(registry.pricing().costUsd("candidate-y", 1000, 1000, 0)).isEmpty();
        assertThat(CellPricingBook.format(registry.pricing().costUsd("candidate-y", 1000, 1000, 0)))
                .isEqualTo("미상");
    }

    @Test
    @DisplayName("시드 기본값이 고정이라 표본이 실행마다 흔들리지 않는다")
    void seedIsStableByDefault() {
        assertThat(CellModelRegistry.resolve(BenchmarkCell.A, Map.of()).seed())
                .isEqualTo(CellModelRegistry.DEFAULT_SEED);
        assertThat(CellModelRegistry.resolve(BenchmarkCell.A,
                Map.of(CellModelRegistry.SEED_PROPERTY, "7")).seed()).isEqualTo(7L);
    }

    @Test
    @DisplayName("cellModels 인라인 표기가 역할 키로 펼쳐진다")
    void inlinePinsParse() {
        assertThat(CellModelRegistry.parseInlinePins("generation=a,escalation=b"))
                .containsEntry(CellModelRegistry.MODEL_PROPERTY_PREFIX + "generation", "a")
                .containsEntry(CellModelRegistry.MODEL_PROPERTY_PREFIX + "escalation", "b");
        assertThatThrownBy(() -> CellModelRegistry.parseInlinePins("generation"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("셀 이름 오타는 조용히 건너뛰지 않고 실패한다")
    void unknownCellNameFails() {
        assertThat(BenchmarkCell.parse("A,D")).containsExactly(BenchmarkCell.A, BenchmarkCell.D);
        assertThat(BenchmarkCell.parse(null)).hasSize(BenchmarkCell.values().length);
        assertThatThrownBy(() -> BenchmarkCell.parse("A,Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("알 수 없는 셀 이름");
    }

    private static String constant(Path root, String file, String name) {
        try {
            String source = Files.readString(root.resolve(file), StandardCharsets.UTF_8);
            Matcher matcher = Pattern
                    .compile("static final String " + name + "\\s*=\\s*\"([^\"]+)\"")
                    .matcher(source);
            assertThat(matcher.find())
                    .as("%s 에서 상수 %s 를 찾지 못했다 — 프로덕션이 바뀌면 이 검사부터 고쳐야 한다",
                            file, name)
                    .isTrue();
            return matcher.group(1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
