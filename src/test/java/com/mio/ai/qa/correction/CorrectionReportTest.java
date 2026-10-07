package com.mio.ai.qa.correction;

import com.mio.ai.qa.correction.CorrectionEvalRunner.ScoredSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("[QA] 정정 측정 리포트 집계 (네트워크 없음)")
class CorrectionReportTest {

    private static final CorrectionEvalCase FACT = new CorrectionEvalCase("E-SECRET-1", "fact", "strong", true,
            List.of(new CorrectionEvalCase.Turn("USER", "a"), new CorrectionEvalCase.Turn("ASSISTANT", "b"),
                    new CorrectionEvalCase.Turn("USER", "c")));
    private static final CorrectionEvalCase EMOTION = new CorrectionEvalCase("E-SECRET-2", "emotion", "weak", true,
            List.of(new CorrectionEvalCase.Turn("USER", "a"), new CorrectionEvalCase.Turn("ASSISTANT", "b"),
                    new CorrectionEvalCase.Turn("USER", "c")));
    private static final CorrectionEvalCase CONTROL = new CorrectionEvalCase("N-SECRET-1", "control", null, false,
            List.of(new CorrectionEvalCase.Turn("USER", "d")));
    private static final CorrectionEvalSet SET = new CorrectionEvalSet("v", List.of(FACT, EMOTION, CONTROL));

    private static ScoredSample scored(String mode, CorrectionPromptArm arm, CorrectionEvalCase evalCase, int repeat,
                                       boolean pass, boolean newAdvice) {
        Map<String, Boolean> items = new LinkedHashMap<>();
        if (evalCase.correction()) {
            items.put(CorrectionVerdict.ACKNOWLEDGED, pass);
            items.put(CorrectionVerdict.REUSED_FRAME, false);
            items.put(CorrectionVerdict.REPEATED_ADVICE, false);
            items.put(CorrectionVerdict.NEW_GENERIC_ADVICE, newAdvice);
            items.put(CorrectionVerdict.RESTATED, pass);
            items.put(CorrectionVerdict.INVENTED_FACTS, false);
        } else {
            items.put(CorrectionVerdict.SPURIOUS_ACKNOWLEDGMENT, !pass);
            items.put(CorrectionVerdict.UNNATURAL_RESTATEMENT, false);
            items.put(CorrectionVerdict.AVOIDED_REQUESTED_ADVICE, false);
        }
        CorrectionVerdict verdict = new CorrectionVerdict(evalCase.id(), evalCase.correction(), items, Map.of(),
                pass, false);
        return new ScoredSample(mode, new CorrectionGenerationRunner.Sample(evalCase.id(), arm, repeat,
                "응답 본문 SECRET", false, false), verdict, CorrectionResponseShape.of("첫째. 둘째. 셋째."));
    }

    private static ScoredSample failedGeneration(String mode, CorrectionPromptArm arm, CorrectionEvalCase evalCase) {
        return new ScoredSample(mode, new CorrectionGenerationRunner.Sample(evalCase.id(), arm, 0, "", true, false),
                null, null);
    }

    private static ScoredSample judgeFailure(String mode, CorrectionPromptArm arm, CorrectionEvalCase evalCase) {
        return new ScoredSample(mode, new CorrectionGenerationRunner.Sample(evalCase.id(), arm, 0, "응답", false, false),
                CorrectionVerdict.failed(evalCase), CorrectionResponseShape.of("응답."));
    }

    private static CorrectionEvalRunner.Result result(List<ScoredSample> samples) {
        return new CorrectionEvalRunner.Result(CorrectionRunIdentity.stamp("v", "sha", 2, List.of("NORMAL"),
                List.of("BASELINE", "WITH_CORRECTION_BLOCK"), "gpt-4o", "gpt-4o-mini"), SET, samples);
    }

    @Test
    @DisplayName("통과율의 분모는 채점까지 끝난 표본이고, 생성·채점 실패는 따로 센다")
    void failuresAreSeparatedFromDenominator() {
        List<ScoredSample> samples = new ArrayList<>();
        samples.add(scored("NORMAL", CorrectionPromptArm.BASELINE, FACT, 0, true, false));
        samples.add(scored("NORMAL", CorrectionPromptArm.BASELINE, FACT, 1, false, true));
        samples.add(failedGeneration("NORMAL", CorrectionPromptArm.BASELINE, EMOTION));
        samples.add(judgeFailure("NORMAL", CorrectionPromptArm.BASELINE, EMOTION));

        CorrectionReport.GroupStats stats = CorrectionReport.aggregate(result(samples)).get(0);

        assertThat(stats.total).isEqualTo(4);
        assertThat(stats.generationFailed).isEqualTo(1);
        assertThat(stats.judgeFailed).isEqualTo(1);
        assertThat(stats.correction.judged()).isEqualTo(2);
        assertThat(stats.correction.passed()).isEqualTo(1);
        assertThat(stats.correction.rate()).isEqualTo(0.5);
        assertThat(stats.correctionItemTrue).containsEntry(CorrectionVerdict.NEW_GENERIC_ADVICE, 1);
    }

    @Test
    @DisplayName("반복 표본이 엇갈린 케이스와 케이스별 통과 비율 평균을 센다")
    void countsUnstableCasesAndCaseMean() {
        List<ScoredSample> samples = List.of(
                scored("NORMAL", CorrectionPromptArm.BASELINE, FACT, 0, true, false),
                scored("NORMAL", CorrectionPromptArm.BASELINE, FACT, 1, false, true),
                scored("NORMAL", CorrectionPromptArm.BASELINE, EMOTION, 0, false, true),
                scored("NORMAL", CorrectionPromptArm.BASELINE, EMOTION, 1, false, true));

        CorrectionReport.GroupStats stats = CorrectionReport.aggregate(result(samples)).get(0);

        assertThat(stats.unstableCorrectionCases()).isEqualTo(1);
        assertThat(stats.meanCasePassRate()).isEqualTo(0.25);
        assertThat(stats.byType.get("fact").passed()).isEqualTo(1);
        assertThat(stats.byStrength.get("weak").judged()).isEqualTo(2);
    }

    @Test
    @DisplayName("모드·팔마다 그룹을 따로 만들고 후보−기준선 차이를 보여 준다")
    void groupsByModeAndArmAndComparesArms() {
        List<ScoredSample> samples = List.of(
                scored("NORMAL", CorrectionPromptArm.BASELINE, FACT, 0, false, true),
                scored("NORMAL", CorrectionPromptArm.WITH_CORRECTION_BLOCK, FACT, 0, true, false),
                scored("SUPPORTIVE", CorrectionPromptArm.BASELINE, FACT, 0, false, true));

        CorrectionEvalRunner.Result result = result(samples);
        String report = CorrectionReport.render(result, null, CorrectionReport.Detail.SUMMARY);

        assertThat(CorrectionReport.aggregate(result)).hasSize(3);
        assertThat(report).contains("모드 NORMAL · 팔 BASELINE", "모드 NORMAL · 팔 WITH_CORRECTION_BLOCK",
                "후보 − 기준선", "0.0% → 100.0% (+100.0%p)");
        // SUPPORTIVE 에는 후보 팔이 없으므로 비교 줄이 없다
        assertThat(report).doesNotContain("모드 SUPPORTIVE: 정정 통과율");
    }

    @Test
    @DisplayName("요약 리포트에는 케이스 id·응답 본문이 없고, 상세 리포트에는 id 별 통과만 있다")
    void summaryHidesCaseContentAndDetailShowsOnlyPassCounts() {
        List<ScoredSample> samples = List.of(
                scored("NORMAL", CorrectionPromptArm.BASELINE, FACT, 0, true, false),
                scored("NORMAL", CorrectionPromptArm.BASELINE, CONTROL, 0, false, false));

        String summary = CorrectionReport.render(result(samples), null, CorrectionReport.Detail.SUMMARY);
        String detail = CorrectionReport.render(result(samples), null, CorrectionReport.Detail.DETAIL);

        assertThat(summary).doesNotContain("SECRET");
        assertThat(detail).contains("E-SECRET-1: 1/1", "N-SECRET-1: 0/1");
        assertThat(detail).doesNotContain("응답 본문");
    }

    @Test
    @DisplayName("표본이 30 미만이면 참고용으로 표시하고, 분모가 0 이면 미보고로 표시한다")
    void marksSmallAndEmptyDenominators() {
        String report = CorrectionReport.render(result(List.of(
                failedGeneration("NORMAL", CorrectionPromptArm.BASELINE, FACT),
                scored("NORMAL", CorrectionPromptArm.BASELINE, CONTROL, 0, true, false))),
                null, CorrectionReport.Detail.SUMMARY);

        assertThat(report).contains("정정 케이스 통과율: 미보고 (n=0)", "대조군(정정 아님) 통과율: 100.0% (1/1) 참고용");
    }

    @Test
    @DisplayName("실제 비용을 컴포넌트별로 싣고, 단가 미등록 호출은 합계에서 빼고 표시한다")
    void rendersActualCostFromLedger() {
        CorrectionCostLedger ledger = new CorrectionCostLedger();
        ledger.record(new CorrectionCostLedger.Call("CORRECTION_EVAL_GENERATION", "gpt-4o", 1000, 200, 0,
                new BigDecimal("0.0045")));
        ledger.record(new CorrectionCostLedger.Call("CORRECTION_EVAL_JUDGE", "unknown-model", 500, 100, 0, null));

        String report = CorrectionReport.render(result(List.of(
                scored("NORMAL", CorrectionPromptArm.BASELINE, FACT, 0, true, false))),
                ledger, CorrectionReport.Detail.SUMMARY);

        assertThat(report).contains("CORRECTION_EVAL_GENERATION: 호출 1 · 입력 1000 · 출력 200 · $0.0045",
                "단가 미등록 호출 1건 제외", "합계: $0.0045");
    }
}
