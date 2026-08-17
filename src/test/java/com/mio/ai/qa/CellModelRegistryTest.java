package com.mio.ai.qa;

import com.mio.ai.llm.ModelRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    /**
     * 벤치마크 역할 → 프로덕션 역할. 프로덕션 기본값의 진실의 원천이 호출부 상수에서
     * {@link ModelRole} 로 옮겨졌다 (#479) — 소스 정규식 파싱 대신 enum 을 직접 읽는다.
     * 프로덕션이 기본 모델을 바꾸면 이 대응을 통해 여기서 깨진다.
     */
    private static final Map<CellModelRole, ModelRole> PRODUCTION_ROLES = Map.of(
            CellModelRole.GENERATION, ModelRole.GENERATION,
            CellModelRole.INPUT_SAFETY, ModelRole.INPUT_JUDGE,
            CellModelRole.OUTPUT_JUDGE, ModelRole.OUTPUT_JUDGE);

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
    @DisplayName("운영 기본값이 프로덕션 기본값과 같다 — 프로덕션이 모델을 바꾸면 여기서 깨진다")
    void operationalDefaultsTrackProductionConstants() {
        CellModelRegistry registry = CellModelRegistry.resolve(BenchmarkCell.A, Map.of());

        PRODUCTION_ROLES.forEach((cellRole, productionRole) ->
                assertThat(registry.modelFor(cellRole))
                        .as("벤치마크 %s 기본값이 프로덕션 %s 기본값과 어긋났다",
                                cellRole, productionRole)
                        .isEqualTo(productionRole.defaultModel()));
        assertThat(PRODUCTION_ROLES).hasSize(3);
    }

    @Test
    @DisplayName("프로덕션 4역할에는 하드코딩된 모델 상수가 남아 있지 않다 — #479 이후의 진실의 원천은 ModelRole 이다")
    void productionCallSitesCarryNoModelConstants() {
        Path root = LockedEvalContaminationScanner.findRepoRoot();
        for (String file : new String[]{
                "src/main/java/com/mio/ai/orchestrator/ConversationOrchestrator.java",
                "src/main/java/com/mio/ai/judge/InputJudge.java",
                "src/main/java/com/mio/ai/judge/OutputJudge.java",
                "src/main/java/com/mio/ai/judge/CbtMetadataClassifier.java"}) {
            assertThat(sourceOf(root, file))
                    .as("%s 에 모델 리터럴이 되살아나면 카탈로그와 두 개의 진실이 생긴다", file)
                    .doesNotContainPattern("static final String \\w*MODEL\\w*\\s*=\\s*\"gpt-");
        }
    }

    @Test
    @DisplayName("메인 생성 호출부가 canary 라우터를 거쳐 카탈로그의 GENERATION 해석을 읽는다")
    void orchestratorReadsGenerationThroughCanaryRouter() {
        // ConversationOrchestrator 는 의존 그래프가 커서 판정 클래스들처럼 요청 캡처
        // 단위 테스트(ModelRoutingWiringTest)를 만들 수 없다. 대신 소스에서 라우팅 체인
        // (orchestrator → canary router → catalog)이 유일한 모델 출처인지 고정한다 —
        // 위 테스트가 상수 부활을 막고, 이 테스트가 조회 자체의 존재를 못박는다.
        Path root = LockedEvalContaminationScanner.findRepoRoot();
        assertThat(sourceOf(root,
                "src/main/java/com/mio/ai/orchestrator/ConversationOrchestrator.java"))
                .contains("generationCanaryRouter.modelFor(userId)")
                .contains("shadowGenerationRunner.maybeShadow(llmRequest)")
                .doesNotContain("\"gpt-4o\"");
        assertThat(sourceOf(root,
                "src/main/java/com/mio/ai/llm/GenerationCanaryRouter.java"))
                .as("라우터의 기본 팔은 카탈로그 해석이어야 한다 — 여기가 끊기면 canary 미설정 시 모델 출처가 사라진다")
                .contains("modelCatalog.modelFor(ModelRole.GENERATION)");
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

    /**
     * 이 sweep 은 <b>쉬운 실수 방지용</b>이다 — 상수 복붙으로 모델 리터럴이 되살아나는 것을
     * 잡는다. 문자열 연결({@code "gp" + "t-..."}) 같은 의도적 우회는 못 잡고, 코드 라인 뒤
     * 인라인 주석에 인용부호로 모델을 언급하면 오탐한다. 그 수준의 회피·언급은 리뷰 몫이다.
     */
    @Test
    @DisplayName("프로덕션 소스 전체에 모델 리터럴이 카탈로그 밖에 없다 — #482 이후 두 개의 진실 금지")
    void noModelLiteralsOutsideTheCatalog() throws IOException {
        Path main = LockedEvalContaminationScanner.findRepoRoot().resolve("src/main/java");
        Pattern literal = Pattern.compile("\"(gpt-|text-embedding-|o3|o4-mini)");

        try (var files = Files.walk(main)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("ModelRole.java"))
                    .filter(p -> {
                        try {
                            return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                                    .map(String::trim)
                                    // 주석은 모델을 언급할 수 있다 — 코드 라인만 본다
                                    .filter(line -> !line.startsWith("//") && !line.startsWith("*")
                                            && !line.startsWith("/*"))
                                    .anyMatch(line -> literal.matcher(line).find());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .map(p -> main.relativize(p).toString())
                    .toList();
            assertThat(offenders)
                    .as("모델 ID 하드코딩이 남은 파일 — ModelRole 에 역할을 추가하고 카탈로그를 주입할 것")
                    .isEmpty();
        }
    }

    private static String sourceOf(Path root, String file) {
        try {
            return Files.readString(root.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
