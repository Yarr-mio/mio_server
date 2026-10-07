package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmPricingProperties;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.prompt.PromptBuilder;
import com.mio.ai.qa.CellTokenEstimator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 실 LLM 을 부르기 전의 <b>무과금 견적</b> (이슈 #552).
 *
 * <p>정확한 토큰 수는 tokenizer 를 실행해야 나오므로 기존 {@link CellTokenEstimator} 의 문자 종류별
 * 근사를 재사용하고, <b>범위로 낸다</b>. 단일 숫자는 견적서가 되고 틀렸을 때 책임을 남긴다.
 *
 * <p>가정을 결과와 함께 싣는다.
 * <ul>
 *   <li>생성 응답 길이: 페르소나의 "2-4문장" 규칙에서 나온 기대값 {@value #EXPECTED_GENERATION_COMPLETION_TOKENS}
 *       토큰. 상한은 프로덕션 출력 토큰 상한.</li>
 *   <li>채점 응답 길이: 판정 JSON + 근거 인용의 기대값 {@value #EXPECTED_JUDGE_COMPLETION_TOKENS} 토큰.
 *       상한은 채점기의 출력 토큰 상한.</li>
 *   <li>캐시 할인은 반영하지 않는다 — 견적은 상한 쪽으로 읽는 것이 안전하다.</li>
 * </ul>
 */
final class CorrectionCostEstimator {

    /** 파일럿 실측(2026-09-21, 생성 80회): 응답 평균 약 52토큰. 여유를 두어 60 으로 잡는다. 상한은 프로덕션 출력 상한. */
    static final int EXPECTED_GENERATION_COMPLETION_TOKENS = 60;
    /** 파일럿 실측(채점 80회): 판정 JSON + 근거 인용 평균 약 70토큰. 75 로 잡는다. 상한은 채점기 출력 상한. */
    static final int EXPECTED_JUDGE_COMPLETION_TOKENS = 75;

    private static final BigDecimal TOKENS_PER_PRICE_UNIT = new BigDecimal("1000000");

    record Estimate(int generationCalls, int judgeCalls, long generationPromptTokens, long judgePromptTokens,
                    BigDecimal lowUsd, BigDecimal expectedUsd, BigDecimal highUsd,
                    Breakdown expectedBreakdown, List<String> unpricedModels) {

        boolean priced() {
            return unpricedModels.isEmpty();
        }
    }

    /** 기대 비용의 항목별 내역 — 어디에서 돈이 나가는지 보여 준다. */
    record Breakdown(BigDecimal generationInput, BigDecimal generationOutput,
                     BigDecimal judgeInput, BigDecimal judgeOutput) {
        static Breakdown zero() {
            return new Breakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private CorrectionCostEstimator() {}

    static Estimate estimate(CorrectionEvalSet set, int repeats, List<GenerationMode> modes,
                             List<CorrectionPromptArm> arms, String generationModel, String judgeModel,
                             LlmPricingProperties pricing) {
        PromptBuilder promptBuilder = new PromptBuilder();
        CorrectionJudge judge = new CorrectionJudge(null, judgeModel);
        String placeholderResponse = "가".repeat(EXPECTED_GENERATION_COMPLETION_TOKENS * 6 / 5);

        long generationPrompt = 0;
        long judgePrompt = 0;
        int calls = 0;
        for (GenerationMode mode : modes) {
            CorrectionGenerationRunner runner = new CorrectionGenerationRunner(
                    null, promptBuilder, generationModel, mode,
                    CorrectionGenerationRunner.PRODUCTION_MAX_COMPLETION_TOKENS);
            for (CorrectionPromptArm arm : arms) {
                for (CorrectionEvalCase evalCase : set.cases()) {
                    generationPrompt += CellTokenEstimator.promptTokens(runner.requestFor(evalCase, arm).messages())
                            * repeats;
                    String system = evalCase.correction()
                            ? CorrectionRubric.CORRECTION_SYSTEM_PROMPT
                            : CorrectionRubric.CONTROL_SYSTEM_PROMPT;
                    judgePrompt += CellTokenEstimator.promptTokens(LlmRequest.of(judgeModel, system,
                            judge.userPrompt(evalCase, placeholderResponse)).messages()) * repeats;
                    calls += repeats;
                }
            }
        }

        List<String> unpriced = new ArrayList<>();
        LlmPricingProperties.ModelPrice generationPrice = price(pricing, generationModel, unpriced);
        LlmPricingProperties.ModelPrice judgePrice = price(pricing, judgeModel, unpriced);
        if (!unpriced.isEmpty()) {
            return new Estimate(calls, calls, generationPrompt, judgePrompt,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Breakdown.zero(), List.copyOf(unpriced));
        }

        BigDecimal expectedPrompt = cost(generationPrice.input(), generationPrompt)
                .add(cost(judgePrice.input(), judgePrompt));
        BigDecimal expectedCompletion = cost(generationPrice.output(), (long) calls * EXPECTED_GENERATION_COMPLETION_TOKENS)
                .add(cost(judgePrice.output(), (long) calls * EXPECTED_JUDGE_COMPLETION_TOKENS));
        BigDecimal ceilingCompletion = cost(generationPrice.output(),
                (long) calls * CorrectionGenerationRunner.PRODUCTION_MAX_COMPLETION_TOKENS)
                .add(cost(judgePrice.output(), (long) calls * CorrectionJudge.MAX_COMPLETION_TOKENS));

        BigDecimal expected = expectedPrompt.add(expectedCompletion);
        BigDecimal low = expected.multiply(BigDecimal.valueOf(CellTokenEstimator.LOWER_MULTIPLIER));
        BigDecimal high = expectedPrompt.multiply(BigDecimal.valueOf(CellTokenEstimator.UPPER_MULTIPLIER))
                .add(ceilingCompletion);
        Breakdown breakdown = new Breakdown(
                cost(generationPrice.input(), generationPrompt),
                cost(generationPrice.output(), (long) calls * EXPECTED_GENERATION_COMPLETION_TOKENS),
                cost(judgePrice.input(), judgePrompt),
                cost(judgePrice.output(), (long) calls * EXPECTED_JUDGE_COMPLETION_TOKENS));
        return new Estimate(calls, calls, generationPrompt, judgePrompt, low, expected, high, breakdown, List.of());
    }

    static String render(Estimate estimate, int repeats, int modes, int arms, String generationModel,
                         String judgeModel) {
        if (!estimate.priced()) {
            return String.format(Locale.ROOT, "  반복 %d: 단가 미등록 모델 %s — 비용을 계산하지 못했다 "
                    + "(호출 수는 생성 %d + 채점 %d)%n", repeats, estimate.unpricedModels(),
                    estimate.generationCalls(), estimate.judgeCalls());
        }
        return String.format(Locale.ROOT,
                "  반복 %d (모드 %d × 팔 %d): 생성 %d회 + 채점 %d회 · 입력 토큰 약 %d만 · 비용 $%s ~ $%s (기대 $%s)%n",
                repeats, modes, arms, estimate.generationCalls(), estimate.judgeCalls(),
                (estimate.generationPromptTokens() + estimate.judgePromptTokens()) / 10_000,
                usd(estimate.lowUsd()), usd(estimate.highUsd()), usd(estimate.expectedUsd()))
                + breakdownLine(estimate);
    }

    private static String breakdownLine(Estimate estimate) {
        Breakdown b = estimate.expectedBreakdown();
        BigDecimal total = b.generationInput().add(b.generationOutput()).add(b.judgeInput()).add(b.judgeOutput());
        if (total.signum() == 0) {
            return "";
        }
        return String.format(Locale.ROOT,
                "      내역(기대): 생성 출력 %s · 생성 입력 %s · 채점 입력 %s · 채점 출력 %s%n",
                share(b.generationOutput(), total), share(b.generationInput(), total),
                share(b.judgeInput(), total), share(b.judgeOutput(), total));
    }

    private static String share(BigDecimal part, BigDecimal total) {
        return String.format(Locale.ROOT, "$%s(%.0f%%)", part.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                part.multiply(BigDecimal.valueOf(100)).divide(total, 4, RoundingMode.HALF_UP).doubleValue());
    }

    /** 세트 파일이 아직 없을 때 규모만으로 견적을 내기 위한 대표 세트. 길이는 가이드의 전형적인 케이스를 따른다. */
    static CorrectionEvalSet syntheticSet(int correctionCases, int controlCases) {
        List<CorrectionEvalCase> cases = new ArrayList<>();
        String[] types = CorrectionEvalCase.CORRECTION_TYPES.stream().sorted().toArray(String[]::new);
        String[] strengths = CorrectionEvalCase.STRENGTHS.stream().sorted().toArray(String[]::new);
        for (int i = 0; i < correctionCases; i++) {
            cases.add(new CorrectionEvalCase("E-" + i, types[i % types.length], strengths[i % strengths.length],
                    true, List.of(
                    new CorrectionEvalCase.Turn("USER", "가".repeat(45)),
                    new CorrectionEvalCase.Turn("ASSISTANT", "나".repeat(110)),
                    new CorrectionEvalCase.Turn("USER", "다".repeat(40)))));
        }
        for (int i = 0; i < controlCases; i++) {
            cases.add(new CorrectionEvalCase("N-" + i, "control", null, false, i % 4 == 0, List.of(
                    new CorrectionEvalCase.Turn("USER", "라".repeat(45)))));
        }
        return new CorrectionEvalSet("synthetic-estimate", cases);
    }

    private static LlmPricingProperties.ModelPrice price(LlmPricingProperties pricing, String model,
                                                         List<String> unpriced) {
        LlmPricingProperties.ModelPrice price = pricing.getModels().get(model);
        if (price == null || !price.isValid()) {
            unpriced.add(model);
            return null;
        }
        return price;
    }

    private static BigDecimal cost(BigDecimal pricePerMillion, long tokens) {
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens)).divide(TOKENS_PER_PRICE_UNIT, 10, RoundingMode.HALF_UP);
    }

    private static String usd(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
