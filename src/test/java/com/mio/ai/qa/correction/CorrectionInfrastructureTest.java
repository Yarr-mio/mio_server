package com.mio.ai.qa.correction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.qa.correction.CorrectionEvalRunner.ScoredSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[QA] 정정 측정 기반 부품: 단가·원장·아카이브·채점 모델 설정")
class CorrectionInfrastructureTest {

    @Test
    @DisplayName("단가를 프로덕션 application.yml 의 openai.pricing.models 에서 읽는다")
    void loadsPricingFromApplicationYml() {
        LlmPricingProperties pricing = CorrectionPricing.load();

        assertThat(pricing.getModels()).containsKeys("gpt-4o", "gpt-4o-mini");
        LlmPricingProperties.ModelPrice gpt4o = pricing.getModels().get("gpt-4o");
        assertThat(gpt4o.isValid()).isTrue();
        assertThat(gpt4o.input()).isPositive();
        assertThat(gpt4o.output()).isGreaterThan(gpt4o.input());
        assertThat(pricing.getModels().get("gpt-4o-mini").input()).isLessThan(gpt4o.input());
    }

    @Test
    @DisplayName("원장은 컴포넌트별로 합산하고, 컴포넌트 없는 기록·단가 미등록은 합계에서 구분한다")
    void ledgerAggregatesByComponent() {
        CorrectionCostLedger ledger = new CorrectionCostLedger();
        ledger.record(new CorrectionCostLedger.Call("GEN", "gpt-4o", 100, 20, 0, new BigDecimal("0.10")));
        ledger.record(new CorrectionCostLedger.Call("GEN", "gpt-4o", 50, 10, 0, new BigDecimal("0.05")));
        ledger.record(new CorrectionCostLedger.Call("JUDGE", "x", 30, 5, 0, null));

        Map<String, CorrectionCostLedger.Totals> by = ledger.byComponent();

        assertThat(by.get("GEN").calls()).isEqualTo(2);
        assertThat(by.get("GEN").promptTokens()).isEqualTo(150);
        assertThat(by.get("GEN").costUsd()).isEqualByComparingTo("0.15");
        assertThat(by.get("JUDGE").unpriced()).isEqualTo(1);
        assertThat(ledger.total().costUsd()).isEqualByComparingTo("0.15");
        assertThat(ledger.total().unpriced()).isEqualTo(1);
    }

    @Test
    @DisplayName("원장 기록기는 컴포넌트가 없는 호출을 무시한다 (프로덕션 기록기와 같은 규칙)")
    void capturingWriterIgnoresCallsWithoutComponent() {
        CorrectionCostLedger ledger = new CorrectionCostLedger();

        ledger.writer().write(null, null, null, "gpt-4o", "m", 1, 1, 0, BigDecimal.ONE, null);
        ledger.writer().write(null, null, "GEN", "gpt-4o", "m", 1, 1, 0, BigDecimal.ONE, null);

        assertThat(ledger.calls()).hasSize(1);
    }

    @Test
    @DisplayName("아카이브는 응답·판정·근거를 파일로 남기고, 실행 신원을 함께 담는다")
    void archiveWritesResponsesAndVerdicts(@TempDir Path dir) throws Exception {
        CorrectionEvalCase evalCase = new CorrectionEvalCase("E-1", "fact", "strong", true, List.of(
                new CorrectionEvalCase.Turn("USER", "a"), new CorrectionEvalCase.Turn("ASSISTANT", "b"),
                new CorrectionEvalCase.Turn("USER", "c")));
        CorrectionVerdict verdict = new CorrectionVerdict("E-1", true,
                Map.of(CorrectionVerdict.ACKNOWLEDGED, true), Map.of(CorrectionVerdict.ACKNOWLEDGED, "제가 잘못"),
                true, false);
        CorrectionRunIdentity identity = CorrectionRunIdentity.stamp("v1", "abc", 3, List.of("NORMAL"),
                List.of("BASELINE"), "gpt-4o", "gpt-4o-mini");
        CorrectionEvalRunner.Result result = new CorrectionEvalRunner.Result(identity,
                new CorrectionEvalSet("v1", List.of(evalCase)), List.of(new ScoredSample("NORMAL",
                new CorrectionGenerationRunner.Sample("E-1", CorrectionPromptArm.BASELINE, 0, "제가 잘못 짚었네요.",
                        false, false), verdict, CorrectionResponseShape.of("제가 잘못 짚었네요."))));

        Path file = CorrectionRunArchive.write(result, dir.resolve("nested/runs"));

        assertThat(file.getFileName().toString()).isEqualTo(identity.runId() + ".json");
        JsonNode root = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(root.at("/identity/setSha256").asText()).isEqualTo("abc");
        assertThat(root.at("/identity/rubricVersion").asText()).isEqualTo(CorrectionRubric.VERSION);
        assertThat(root.at("/samples/0/response").asText()).isEqualTo("제가 잘못 짚었네요.");
        assertThat(root.at("/samples/0/verdict/passed").asBoolean()).isTrue();
        assertThat(root.at("/samples/0/verdict/evidence/acknowledged").asText()).isEqualTo("제가 잘못");
    }

    @Test
    @DisplayName("실 LLM 클라이언트는 프로덕션 OpenAiLlmClient 로 만들어진다 (호출은 하지 않는다)")
    void realClientIsBuiltFromProductionClient() {
        assertThat(CorrectionClients.real("sk-not-a-real-key", new CorrectionCostLedger()))
                .isInstanceOf(com.mio.ai.llm.OpenAiLlmClient.class);
    }

    @Test
    @DisplayName("채점 모델은 시스템 속성 → 환경 변수 → 기본값 순으로 정한다")
    void judgeModelResolutionOrder() {
        assertThat(CorrectionJudge.configuredModel(k -> null, k -> null)).isEqualTo("gpt-4o-mini");
        assertThat(CorrectionJudge.configuredModel(k -> null,
                k -> CorrectionJudge.MODEL_ENV.equals(k) ? " gpt-4.1 " : null)).isEqualTo("gpt-4.1");
        assertThat(CorrectionJudge.configuredModel(
                k -> CorrectionJudge.MODEL_PROPERTY.equals(k) ? "prop-model" : null,
                k -> "env-model")).isEqualTo("prop-model");
        assertThat(CorrectionJudge.configuredModel(k -> "  ", k -> "")).isEqualTo("gpt-4o-mini");
    }
}
