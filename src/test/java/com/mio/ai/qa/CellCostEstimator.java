package com.mio.ai.qa;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 모델을 한 번도 부르지 않고 A~E 실행 비용을 추정한다.
 *
 * <p>돌리기 전에 청구서를 볼 수 있어야 한다. 그래서 이 추정은 {@code @Tag("llm-integration")}
 * 없이 기본 {@code ./gradlew test} 에서 도는 평범한 단위 테스트로 실행된다
 * ({@link CellCostEstimateTest}).
 *
 * <h2>어떻게 부르지 않고 재는가</h2>
 *
 * <p>프롬프트를 손으로 다시 적어 재지 않는다. {@link CellRunner#stubbed} 로 <b>실행과 같은
 * 코드 경로</b>를 태우되 마지막 HTTP 호출만 {@link StubLlmClient} 로 바꾼다. 프로덕션이 실제로
 * 조립한 프롬프트가 그대로 만들어지고, 그 문자열을 {@link CellTokenEstimator} 가 센다.
 * 프롬프트가 길어지면 견적도 같이 늘어난다.
 *
 * <h2>이 숫자를 어떻게 읽어야 하는가</h2>
 *
 * <ul>
 *   <li><b>상한 쪽으로 치우친 추정이다.</b> 스텁 판정이 항상 {@code CLEAR_LOW} 라 위기 고정
 *       플로우로 빠져 생성을 건너뛰는 케이스가 견적에서는 전부 생성으로 잡힌다. 실제 실행은
 *       이보다 적게 나올 가능성이 높다.</li>
 *   <li><b>escalation 재시도는 관측값이 0 이다.</b> 스텁 응답은 계약 위반을 일으키지 않아
 *       셀 D·E 의 재생성이 발동하지 않는다. 그래서 관측치와 별도로
 *       {@link EscalationCeiling} — <b>정량 상한</b>을 함께 낸다. 상한은 "생성이 일어난 모든
 *       턴이 한 번씩 재시도했다" 는 최악을 그대로 계산한 값이며, 재시도가 턴당 한 번으로
 *       하드 제한돼 있으므로({@code CellRunner.escalate}) 실제 천장이다.</li>
 *   <li><b>토큰은 문자 기반 근사다.</b> 그래서 점추정이 아니라
 *       {@link CellTokenEstimator#LOWER_MULTIPLIER}~{@link CellTokenEstimator#UPPER_MULTIPLIER}
 *       구간으로 낸다.</li>
 *   <li><b>단가 미등록 모델은 0 이 아니라 미상이다.</b> 상위 모델 후보를 핀하지 않으면 그
 *       셀의 금액은 나오지 않고 토큰 볼륨과 민감도표만 나온다.</li>
 * </ul>
 */
final class CellCostEstimator {

    /**
     * 단가 미상 모델의 민감도 배수.
     *
     * <p>후보를 핀하기 전에도 "대충 얼마인가" 를 알아야 승인 여부를 판단할 수 있다. 다만
     * 하나의 숫자를 내면 그게 견적서가 되므로, <b>현행 {@code gpt-4o} 단가의 배수</b>라는
     * 명시적 가정 위에서 여러 값을 함께 낸다.
     */
    private static final List<BigDecimal> SENSITIVITY_MULTIPLIERS =
            List.of(new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("5"));

    private static final String REFERENCE_MODEL = "gpt-4o";

    private static final String GENERATION_COMPONENT = "MAIN_GENERATION";

    /**
     * 추론 모델의 completion 토큰 배수.
     *
     * <p>o 계열과 {@code -pro} 계열은 내부 추론 토큰을 만들고 그것이 <b>출력 단가로 과금된다.</b>
     * 이 견적의 completion 토큰은 스텁 응답의 글자 수에서 나오므로 추론 토큰이 통째로 빠져 있고,
     * 그래서 추론 모델의 금액은 <b>구조적으로 과소</b>다. 배수를 곱해 그 사실을 숫자로 만든다.
     *
     * <p>기본값 4배는 관측이 아니라 <b>가정</b>이다. 실제 배수는 케이스 난이도와 모델에 따라
     * 크게 흔들린다. 그래서 이 값이 들어간 금액에는 "상한이 아니다" 표시가 따라붙고, 실 실행이
     * 한 번이라도 있으면 그때부터는 제공자가 보고한 실측 토큰을 쓴다.
     */
    static final String REASONING_MULTIPLIER_PROPERTY = "mio.eval.reasoningMultiplier";
    static final double DEFAULT_REASONING_MULTIPLIER = 4.0;

    static double reasoningMultiplier() {
        String raw = System.getProperty(REASONING_MULTIPLIER_PROPERTY);
        return raw == null || raw.isBlank()
                ? DEFAULT_REASONING_MULTIPLIER
                : Double.parseDouble(raw.trim());
    }

    private CellCostEstimator() {
    }

    /**
     * 추론 토큰 보정.
     *
     * <p>{@code extraUsd} 가 비면 그 모델의 단가를 모르는 것이다 — 0 이 아니다.
     */
    record ReasoningAdjustment(String model, double multiplier, long extraCompletionTokens,
                               Optional<BigDecimal> extraUsd) {

        String display() {
            return "%s ×%.1f → completion +%d · %s (관측 아님, 가정)".formatted(
                    model, multiplier, extraCompletionTokens, CellPricingBook.format(extraUsd));
        }
    }

    /**
     * escalation 재시도의 <b>정량 상한</b>.
     *
     * <p>"과소 추정" 이라고만 적으면 읽는 사람이 그 폭을 모른 채 하한을 천장으로 읽는다.
     * 재시도는 턴당 최대 한 번이므로 최악은 "생성한 모든 턴이 한 번 더 생성한다" 이고, 그
     * 경우의 추가 호출·토큰·금액을 그대로 계산해 둔다.
     *
     * @param extraUsd 재시도 모델의 단가가 없으면 {@link Optional#empty()} — 0 이 아니다
     */
    record EscalationCeiling(String model, long extraCalls, long promptTokens,
                             long completionTokens, Optional<BigDecimal> extraUsd) {

        String display() {
            return "최악 +%d회 · prompt %d / completion %d · %s".formatted(
                    extraCalls, promptTokens, completionTokens, CellPricingBook.format(extraUsd));
        }
    }

    /**
     * 셀 하나의 견적.
     *
     * @param knownUsd     단가가 등록된 모델들의 비용 합
     * @param unknownModels 단가를 모르는 모델과 그 토큰 볼륨
     */
    record CellEstimate(CellVariant variant, int cases, int llmCalls,
                        long promptTokens, long completionTokens,
                        BigDecimal knownUsd, Map<String, long[]> unknownModels,
                        Map<String, long[]> tokensByModel, Map<String, Long> callsByComponent,
                        Optional<EscalationCeiling> escalationCeiling,
                        List<ReasoningAdjustment> reasoningAdjustments) {

        CellEstimate {
            reasoningAdjustments = List.copyOf(reasoningAdjustments);
        }

        BenchmarkCell cell() {
            return variant.cell();
        }

        boolean complete() {
            return unknownModels.isEmpty();
        }

        /** 추론 모델이 끼어 있으면 이 견적은 상한이 아니다. 리포트가 그 말을 하도록 쓴다. */
        boolean hasReasoningModel() {
            return !reasoningAdjustments.isEmpty();
        }

        /** 추론 토큰 가정까지 얹은 금액. 단가를 모르는 추론 모델이 있으면 미상이다. */
        Optional<BigDecimal> withReasoningUsd() {
            if (!complete()) {
                return Optional.empty();
            }
            BigDecimal total = highUsd();
            for (ReasoningAdjustment adjustment : reasoningAdjustments) {
                if (adjustment.extraUsd().isEmpty()) {
                    return Optional.empty();
                }
                total = total.add(adjustment.extraUsd().get());
            }
            return Optional.of(total.setScale(4, RoundingMode.HALF_UP));
        }

        /** 토큰 추정 오차를 반영한 하한. */
        BigDecimal lowUsd() {
            return scaled(CellTokenEstimator.LOWER_MULTIPLIER);
        }

        /** 토큰 추정 오차를 반영한 상한. 승인 판단은 이 값으로 하는 편이 안전하다. */
        BigDecimal highUsd() {
            return scaled(CellTokenEstimator.UPPER_MULTIPLIER);
        }

        /**
         * escalation 최악까지 포함한 상한.
         *
         * <p>승인 금액은 이 값으로 본다. escalation 모델의 단가를 모르면 <b>상한 자체가
         * 미상</b>이다 — 아는 부분만으로 만든 상한은 상한이 아니다.
         */
        Optional<BigDecimal> ceilingUsd() {
            if (!complete()) {
                return Optional.empty();
            }
            Optional<BigDecimal> escalation = escalationCeiling
                    .map(EscalationCeiling::extraUsd)
                    .orElse(Optional.of(BigDecimal.ZERO));
            return escalation.map(extra -> highUsd()
                    .add(extra.multiply(BigDecimal.valueOf(CellTokenEstimator.UPPER_MULTIPLIER),
                            MathContext.DECIMAL64))
                    .setScale(4, RoundingMode.HALF_UP));
        }

        private BigDecimal scaled(double multiplier) {
            return knownUsd.multiply(BigDecimal.valueOf(multiplier), MathContext.DECIMAL64)
                    .setScale(4, RoundingMode.HALF_UP);
        }
    }

    record Projection(List<CellEstimate> estimates, CellPricingBook pricing, int caseCount) {

        Projection {
            estimates = List.copyOf(estimates);
        }

        BigDecimal totalKnownUsd() {
            return estimates.stream().map(CellEstimate::knownUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        boolean complete() {
            return estimates.stream().allMatch(CellEstimate::complete);
        }

        /** 후보별 합계. 스크리닝 견적에서 "후보 하나당 얼마인가" 를 바로 읽게 한다. */
        Map<String, List<CellEstimate>> byCandidate() {
            Map<String, List<CellEstimate>> grouped = new LinkedHashMap<>();
            estimates.forEach(estimate -> grouped
                    .computeIfAbsent(estimate.variant().frontierCandidate() == null
                            ? "(후보 없음 — registry 핀/기본값)"
                            : estimate.variant().frontierCandidate(), key -> new ArrayList<>())
                    .add(estimate));
            return grouped;
        }
    }

    /** 셀 목록을 잠금 세트 기준으로 견적낸다. 후보 스크리닝이 아닌 기존 경로. */
    static Projection project(List<BenchmarkCell> cells, List<LockedEvalSet.LockedCase> cases) {
        return projectVariants(cells.stream().map(CellVariant::of).toList(), Map.of(), cases);
    }

    /**
     * 후보 스크리닝 견적 — 셀 × 후보 조합의 청구서를 실행 전에 본다.
     *
     * @param pins {@code -PcellPrices} 로 핀한 단가 등. 단가를 넣으면 금액이, 안 넣으면 미상이 나온다
     */
    static Projection projectScreening(List<BenchmarkCell> cells, List<String> candidates,
                                       Map<String, String> pins,
                                       List<LockedEvalSet.LockedCase> cases) {
        return projectVariants(CellVariant.expand(cells, candidates), pins, cases);
    }

    static Projection projectVariants(List<CellVariant> variants, Map<String, String> pins,
                                      List<LockedEvalSet.LockedCase> cases) {
        List<CellEstimate> estimates = new ArrayList<>();
        CellPricingBook pricing = null;
        RunIdentity identity = RunIdentity.stamp(
                pins.getOrDefault(CellModelRegistry.PRICING_AS_OF_PROPERTY,
                        EvalRunManifest.PRICING_DATE_UNRECORDED));
        for (CellVariant variant : variants) {
            CellModelRegistry registry = resolveForEstimate(variant, pins);
            pricing = registry.pricing();
            estimates.add(estimate(variant, registry, cases, identity));
        }
        return new Projection(estimates,
                pricing != null ? pricing : CellPricingBook.load(Map.of(), null), cases.size());
    }

    /** 후보가 있으면 후보로, 없으면 자리 표시자로 상위 모델 역할을 채운다. */
    private static CellModelRegistry resolveForEstimate(CellVariant variant,
                                                        Map<String, String> pins) {
        if (variant.frontierCandidate() == null) {
            return CellModelRegistry.resolveForEstimate(variant.cell(), pins);
        }
        return CellModelRegistry.resolveForVariant(variant, pins);
    }

    private static CellEstimate estimate(CellVariant variant, CellModelRegistry registry,
                                         List<LockedEvalSet.LockedCase> cases,
                                         RunIdentity identity) {
        CellRunner.Result result;
        try {
            result = CellRunner.stubbed(variant, registry).run(cases, false, identity);
        } catch (Exception e) {
            throw new IllegalStateException("셀 %s 견적 실행 실패".formatted(variant.label()), e);
        }

        Map<String, long[]> tokensByModel = new TreeMap<>();
        Map<String, long[]> unknown = new TreeMap<>();
        Map<String, Long> callsByComponent = new TreeMap<>();
        long[] generationTokens = {0, 0, 0};
        BigDecimal known = BigDecimal.ZERO;
        for (CellTokenLedger.Call call : result.ledger().calls()) {
            callsByComponent.merge(call.component(), 1L, Long::sum);
            long[] tokens = tokensByModel.computeIfAbsent(call.model(), k -> new long[]{0, 0, 0});
            tokens[0] += call.promptTokens();
            tokens[1] += call.completionTokens();
            tokens[2]++;
            if (GENERATION_COMPONENT.equals(call.component())) {
                generationTokens[0] += call.promptTokens();
                generationTokens[1] += call.completionTokens();
                generationTokens[2]++;
            }
            if (call.priced()) {
                known = known.add(call.costUsd());
            } else {
                long[] u = unknown.computeIfAbsent(call.model(), k -> new long[]{0, 0, 0});
                u[0] += call.promptTokens();
                u[1] += call.completionTokens();
                u[2]++;
            }
        }
        return new CellEstimate(variant, cases.size(), result.ledger().calls().size(),
                result.ledger().promptTokens(), result.ledger().completionTokens(),
                known.setScale(4, RoundingMode.HALF_UP), unknown, tokensByModel,
                callsByComponent, escalationCeiling(variant, registry, generationTokens),
                reasoningAdjustments(registry, tokensByModel));
    }

    /**
     * 추론 모델의 completion 토큰 보정.
     *
     * <p>명부에 없는 모델은 "모른다" 이므로 보수적으로 추론 모델로 본다
     * ({@link CellCandidateRoster#treatAsReasoningModel}). 자리 표시자(미핀 후보)는 이미 단가가
     * 미상이라 금액이 나오지 않으므로 보정 대상에서 뺀다 — 미상에 배수를 곱해도 미상이다.
     */
    private static List<ReasoningAdjustment> reasoningAdjustments(CellModelRegistry registry,
                                                                  Map<String, long[]> tokensByModel) {
        CellCandidateRoster roster = CellCandidateRoster.load();
        double multiplier = reasoningMultiplier();
        List<ReasoningAdjustment> adjustments = new ArrayList<>();
        tokensByModel.forEach((model, tokens) -> {
            if (CellModelRegistry.UNPINNED_PLACEHOLDER.equals(model)
                    || !roster.treatAsReasoningModel(model)) {
                return;
            }
            long extra = Math.round(tokens[1] * (multiplier - 1.0));
            if (extra <= 0) {
                return;
            }
            adjustments.add(new ReasoningAdjustment(model, multiplier, extra,
                    registry.pricing().costUsd(model, 0L, extra, 0L)));
        });
        return adjustments;
    }

    /**
     * escalation 최악 상한을 계산한다.
     *
     * <p>escalation 이 꺼진 셀은 상한이 없다({@link Optional#empty()}). 켜진 셀은 "생성이
     * 일어난 모든 턴이 한 번씩 재시도" 를 최악으로 잡는다. 재시도 프롬프트는 첫 생성과 같은
     * 프롬프트를 다시 보내므로 prompt 토큰이 같고, completion 은 같은 상한을 쓴다.
     */
    private static Optional<EscalationCeiling> escalationCeiling(CellVariant variant,
                                                                 CellModelRegistry registry,
                                                                 long[] generationTokens) {
        if (!variant.cell().harness().escalationEnabled() || generationTokens[2] == 0) {
            return Optional.empty();
        }
        CellModelRole role = registry.has(CellModelRole.ESCALATION)
                ? CellModelRole.ESCALATION
                : CellModelRole.GENERATION;
        String model = registry.modelFor(role);
        return Optional.of(new EscalationCeiling(model, generationTokens[2],
                generationTokens[0], generationTokens[1],
                registry.pricing().costUsd(model, generationTokens[0], generationTokens[1], 0L)));
    }

    // ── 출력 ──────────────────────────────────────────────────────

    static String render(Projection projection) {
        StringBuilder out = new StringBuilder();
        out.append("\n══════════════════════════════════════════════════════════════\n");
        out.append("  A~E 셀 벤치마크 비용 추정 (모델 호출 없음)\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append("  잠금 세트 %d건 · 단가 출처 %s · 기준일 %s%n".formatted(
                projection.caseCount(), CellPricingBook.SOURCE, projection.pricing().pricingAsOf()));
        out.append("  토큰은 문자 기반 근사다 — 금액은 점값이 아니라 %.0f~%.0f%% 구간으로 읽는다.%n"
                .formatted(CellTokenEstimator.LOWER_MULTIPLIER * 100,
                        CellTokenEstimator.UPPER_MULTIPLIER * 100));

        out.append("\n  %-22s %6s %10s %12s %16s %s%n"
                .formatted("변형", "호출", "prompt", "completion", "추정 USD", "비고"));
        projection.estimates().forEach(estimate -> out.append("  %-22s %6d %10d %12d %16s %s%n"
                .formatted(estimate.variant().label(), estimate.llmCalls(),
                        estimate.promptTokens(), estimate.completionTokens(),
                        "$%s~$%s%s".formatted(estimate.lowUsd().toPlainString(),
                                estimate.highUsd().toPlainString(),
                                estimate.complete() ? "" : " (일부)"),
                        estimate.complete() ? ""
                                : "단가 미상 모델 포함 — 금액은 등록분만, 아래 민감도표 참조")));

        out.append("\n  [역할별 호출 수 — §11.3 '호출 수']\n");
        projection.estimates().forEach(estimate -> out.append("    %-22s %s%n"
                .formatted(estimate.variant().label(), estimate.callsByComponent())));

        BigDecimal total = projection.totalKnownUsd();
        out.append("\n  합계(단가 등록분): $%s ~ $%s%n".formatted(
                total.multiply(BigDecimal.valueOf(CellTokenEstimator.LOWER_MULTIPLIER))
                        .setScale(4, RoundingMode.HALF_UP).toPlainString(),
                total.multiply(BigDecimal.valueOf(CellTokenEstimator.UPPER_MULTIPLIER))
                        .setScale(4, RoundingMode.HALF_UP).toPlainString()));
        if (!projection.complete()) {
            out.append("  ** 단가 미상 모델이 있어 위 합계는 전체 비용이 아니다. "
                    + "0 이 아니라 '아직 모른다' 이다. **\n");
        }

        appendEscalationCeiling(out, projection);
        appendReasoning(out, projection);
        appendCandidateTotals(out, projection);
        appendSensitivity(out, projection);
        appendAssumptions(out);
        out.append("══════════════════════════════════════════════════════════════\n");
        return out.toString();
    }

    /** MEDIUM-4 — "과소" 를 문장이 아니라 숫자로 적는다. */
    private static void appendEscalationCeiling(StringBuilder out, Projection projection) {
        List<CellEstimate> withCeiling = projection.estimates().stream()
                .filter(estimate -> estimate.escalationCeiling().isPresent())
                .toList();
        if (withCeiling.isEmpty()) {
            return;
        }
        out.append("\n  [escalation 재시도 상한 — 관측 0회, 최악을 정량화한 값]\n");
        out.append("  ** 스텁은 계약 위반을 내지 않아 재시도가 발동하지 않는다. 그래서 위 금액은 "
                + "셀 D·E 의 천장이 아니다. 아래가 천장이다. **\n");
        out.append("  ** 재시도는 턴당 최대 1회로 코드에 하드 제한돼 있다 (CellRunner.escalate) — "
                + "그래서 '모든 생성 턴이 1회 재시도' 가 실제 최악이다. **\n");
        withCeiling.forEach(estimate -> {
            EscalationCeiling ceiling = estimate.escalationCeiling().orElseThrow();
            out.append("    %-22s 모델 %s · %s%n".formatted(estimate.variant().label(),
                    ceiling.model(), ceiling.display()));
            out.append("      최악 포함 상한 %s%n".formatted(
                    CellPricingBook.format(estimate.ceilingUsd())));
        });
    }

    /**
     * 추론 토큰 보정.
     *
     * <p>스텁이 만드는 completion 토큰에는 내부 추론 토큰이 없다. 그런데 그 토큰은 출력
     * 단가로 과금된다 — 즉 추론 모델의 견적은 다른 모델과 달리 <b>구조적으로</b> 과소다.
     * "상한 쪽으로 읽으면 안전하다" 는 이 견적의 기본 안내가 추론 모델에는 성립하지 않으므로,
     * 그 사실을 별도 절로 못박는다.
     */
    private static void appendReasoning(StringBuilder out, Projection projection) {
        List<CellEstimate> withReasoning = projection.estimates().stream()
                .filter(CellEstimate::hasReasoningModel)
                .toList();
        if (withReasoning.isEmpty()) {
            return;
        }
        out.append("\n  [추론 토큰 보정 — 관측이 아니라 가정]\n");
        out.append("  ** o 계열·pro 계열은 내부 추론 토큰을 출력 단가로 과금한다. 스텁 견적에는 "
                + "그 토큰이 통째로 없다. **\n");
        out.append("  ** 그래서 추론 모델이 낀 셀의 금액은 상한이 아니다. 아래는 ×%.1f 가정을 "
                + "얹은 값이며, 실 실행 뒤에는 제공자 실측 토큰으로 대체한다 "
                + "(-D%s 로 배수를 바꾼다). **%n"
                .formatted(reasoningMultiplier(), REASONING_MULTIPLIER_PROPERTY));
        withReasoning.forEach(estimate -> {
            out.append("    %-22s%n".formatted(estimate.variant().label()));
            estimate.reasoningAdjustments().forEach(adjustment ->
                    out.append("      %s%n".formatted(adjustment.display())));
            out.append("      가정 포함 금액 %s%n".formatted(
                    CellPricingBook.format(estimate.withReasoningUsd())));
        });
    }

    /** 후보 스크리닝 견적에서 "후보 하나 돌리는 데 얼마" 를 바로 읽게 한다. */
    private static void appendCandidateTotals(StringBuilder out, Projection projection) {
        Map<String, List<CellEstimate>> byCandidate = projection.byCandidate();
        if (byCandidate.size() <= 1) {
            return;
        }
        out.append("\n  [후보별 합계 — 이 후보를 한 번 돌리는 비용]\n");
        byCandidate.forEach((candidate, estimates) -> {
            boolean complete = estimates.stream().allMatch(CellEstimate::complete);
            BigDecimal low = estimates.stream().map(CellEstimate::lowUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal high = estimates.stream().map(CellEstimate::highUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.append("    %-24s $%s ~ $%s%s%n".formatted(candidate,
                    low.toPlainString(), high.toPlainString(),
                    complete ? "" : "  ← 단가 미상 포함, 이 값은 전체 비용이 아니다"));
        });
        out.append("    ↑ 기준선 A 는 후보와 무관하게 한 번만 돈다 — 후보 수만큼 곱하지 않는다.\n");
    }

    /** 단가 미상 모델의 비용을 현행 {@code gpt-4o} 단가의 배수 가정으로 펼친다. */
    private static void appendSensitivity(StringBuilder out, Projection projection) {
        Map<String, Map<String, long[]>> unknown = new LinkedHashMap<>();
        projection.estimates().stream()
                .filter(e -> !e.complete())
                .forEach(e -> unknown.put(e.variant().label(), e.unknownModels()));
        if (unknown.isEmpty()) {
            return;
        }
        var reference = projection.pricing().prices().get(REFERENCE_MODEL);
        out.append("\n  [단가 미상 모델의 민감도 — %s 단가의 배수 가정]\n".formatted(REFERENCE_MODEL));
        out.append("  ** 이것은 견적이 아니라 가정이다. 실제 후보를 핀하면 실제 단가로 다시 낸다. **\n");
        if (reference == null) {
            out.append("    기준 모델 단가가 설정에 없어 민감도표를 낼 수 없다.\n");
            return;
        }
        unknown.forEach((label, models) -> models.forEach((model, tokens) -> {
            out.append("    변형 %s · %s · prompt %d / completion %d%n"
                    .formatted(label, model, tokens[0], tokens[1]));
            SENSITIVITY_MULTIPLIERS.forEach(multiplier -> {
                BigDecimal usd = reference.input().multiply(multiplier)
                        .multiply(BigDecimal.valueOf(tokens[0]))
                        .add(reference.output().multiply(multiplier)
                                .multiply(BigDecimal.valueOf(tokens[1])))
                        .divide(new BigDecimal("1000000"), MathContext.DECIMAL64);
                out.append("      %sx → $%s%n".formatted(multiplier.toPlainString(),
                        usd.setScale(4, RoundingMode.HALF_UP).toPlainString()));
            });
        }));
    }

    private static void appendAssumptions(StringBuilder out) {
        out.append("\n  [가정 — 이 숫자를 인용할 때 같이 인용한다]\n");
        out.append("    · 스텁 판정이 항상 CLEAR_LOW 라 위기 고정 플로우로 빠지는 케이스도 생성으로 잡힌다 (과대)\n");
        out.append("    · escalation 재시도 관측치는 0 이다 — 정량 상한은 위 [escalation 재시도 상한] 절에 있다\n");
        out.append("    · CBT 메타데이터 분류는 프로덕션과 같이 매 전달 턴 1회로 잡혀 있다 (제외 아님)\n");
        out.append("    · L0 Moderation 호출은 하네스가 하지 않으므로 비용에 없다\n");
        out.append("    · 재시도(429)·부분 실패의 추가 비용은 포함하지 않는다\n");
        out.append("    · 단가는 short-context 요율이다. 이 하네스의 프롬프트는 ~1.5k 토큰이라 그 구간에 있지만, "
                + "long-context 요율이 따로 있는 모델은 그 구간에서 대략 2배다 — 프롬프트를 키운 뒤에는 다시 확인한다\n");
        out.append("    · 캐시 입력 단가가 공표되지 않은 모델은 '-' 로 핀한다. 0 으로 적으면 캐시 히트가 공짜로 집계된다\n");
        out.append("    · " + BatchQualityMode.CACHING_NOTE + "\n");
        out.append("    · 실행 후에는 이 추정을 쓰지 않는다 — 제공자가 보고한 실측 토큰이 아카이브에 남는다\n");
    }

    // ── 단계별 견적 ────────────────────────────────────────────────

    /**
     * 한 단계의 실행 계획.
     *
     * @param note 이 계획의 후보가 어디서 왔는지. 2·3단계는 앞 단계 결과를 보고 사람이 정하므로,
     *             견적 시점에는 <b>가정</b>이라는 사실이 숫자와 함께 남아야 한다
     */
    record StagePlan(BenchmarkStage stage, List<BenchmarkCell> cells, List<String> candidates,
                     String note) {

        StagePlan {
            cells = List.copyOf(cells);
            candidates = List.copyOf(candidates);
        }

        List<LockedEvalSet.LockedCase> cases() {
            return stage.sampleSize() == BenchmarkStage.ALL
                    ? LockedEvalSet.CASES
                    : StratifiedSampler.sample(LockedEvalSet.CASES,
                    LockedEvalSet.LockedCase::subgroup, stage.sampleSize(),
                    CellModelRegistry.DEFAULT_SEED);
        }
    }

    record StageProjection(StagePlan plan, Projection projection) {}

    /**
     * 기본 깔때기 계획.
     *
     * <p>1단계는 명부가 정한다. 2·3단계 후보는 아직 존재하지 않으므로 <b>개수만</b> 가정해
     * 자리를 채운다 — 금액의 크기를 미리 보기 위한 것이고, 실제 후보는 앞 단계 결과를 사람이
     * 읽고 정한다. 그 사실이 {@link StagePlan#note} 로 리포트에 그대로 실린다.
     */
    static List<StagePlan> defaultPlan(CellCandidateRoster roster) {
        List<String> screening = roster.screeningCandidates();
        int semifinalCount = CandidateElimination.thresholds(BenchmarkStage.SCREEN).keepTop();
        List<String> semifinal = screening.stream().limit(semifinalCount).toList();
        List<String> finalists = screening.stream().limit(2).toList();
        return List.of(
                new StagePlan(BenchmarkStage.SCREEN, List.of(BenchmarkCell.A, BenchmarkCell.B),
                        screening,
                        "명부 %s 의 생성 후보 %d개 전부".formatted(roster.version(), screening.size())),
                new StagePlan(BenchmarkStage.SEMIFINAL, List.of(BenchmarkCell.A, BenchmarkCell.B),
                        semifinal,
                        "가정: 1단계 생존자 %d개. 실제 후보는 1단계 탈락 계산을 읽고 사람이 정한다"
                                .formatted(semifinalCount)),
                new StagePlan(BenchmarkStage.FULL,
                        List.of(BenchmarkCell.values()), finalists,
                        "가정: 역할별 결선 2개. 실제 후보는 2단계 결과를 읽고 사람이 정한다"));
    }

    static List<StageProjection> projectPlan(List<StagePlan> plans, Map<String, String> pins) {
        List<StageProjection> projections = new ArrayList<>();
        plans.forEach(plan -> projections.add(new StageProjection(plan,
                projectScreening(plan.cells(), plan.candidates(), pins, plan.cases()))));
        return projections;
    }

    /**
     * 단계별·누적 청구서.
     *
     * <p>사람이 단계마다 승인한다는 것이 이 깔때기의 전제다. 승인은 그 단계의 금액과 <b>여기까지
     * 쓴 총액</b>을 같이 봐야 할 수 있으므로 둘 다 낸다.
     */
    static String renderPlan(List<StageProjection> projections) {
        StringBuilder out = new StringBuilder();
        out.append("\n══════════════════════════════════════════════════════════════\n");
        out.append("  모델 선정 깔때기 — 단계별·누적 견적 (모델 호출 없음)\n");
        out.append("══════════════════════════════════════════════════════════════\n");
        out.append("  ** 단계마다 사람이 승인한다. 아래 '누적' 이 그 시점까지의 총 청구서다. **\n");

        BigDecimal cumulativeLow = BigDecimal.ZERO;
        BigDecimal cumulativeHigh = BigDecimal.ZERO;
        boolean anyIncomplete = false;
        for (StageProjection stage : projections) {
            Projection projection = stage.projection();
            BigDecimal low = projection.totalKnownUsd()
                    .multiply(BigDecimal.valueOf(CellTokenEstimator.LOWER_MULTIPLIER))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal high = projection.estimates().stream()
                    .map(estimate -> estimate.withReasoningUsd().orElse(estimate.highUsd()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(4, RoundingMode.HALF_UP);
            cumulativeLow = cumulativeLow.add(low);
            cumulativeHigh = cumulativeHigh.add(high);
            anyIncomplete |= !projection.complete();

            out.append("\n  [%s]\n".formatted(stage.plan().stage().describe()));
            out.append("    후보 %d개 · 셀 %s · 케이스 %d건 · 변형 %d개%n".formatted(
                    stage.plan().candidates().size(), stage.plan().cells(),
                    projection.caseCount(), projection.estimates().size()));
            out.append("    후보 출처: %s%n".formatted(stage.plan().note()));
            out.append("    이 단계   $%s ~ $%s%s%n".formatted(low.toPlainString(),
                    high.toPlainString(),
                    projection.complete() ? "" : "  ← 단가 미상 포함, 전체 비용이 아니다"));
            out.append("    누적      $%s ~ $%s%n".formatted(
                    cumulativeLow.toPlainString(), cumulativeHigh.toPlainString()));
            if (projection.estimates().stream().anyMatch(CellEstimate::hasReasoningModel)) {
                out.append(("    ** 추론 모델 포함 — 상한 쪽 금액은 추론 토큰 ×%.1f 가정을 얹은 값이고, "
                        + "천장이 아니다. **%n").formatted(reasoningMultiplier()));
            }
            if (stage.plan().stage() == BenchmarkStage.SCREEN) {
                out.append(("    batch 품질 모드 적용 시 (입력·출력 50%% 할인): $%s ~ $%s%n"
                        + "      ↑ 1단계의 생성 품질 축에만 쓸 수 있다. 스트리밍이 없어 지연·전달 "
                        + "지표는 측정되지 않으며, 그 사실이 리포트에 미측정으로 남는다.%n")
                        .formatted(BatchQualityMode.discounted(low).toPlainString(),
                                BatchQualityMode.discounted(high).toPlainString()));
            }
            appendPerCandidate(out, projection);
        }

        out.append("\n  깔때기 전체 누적: $%s ~ $%s%n".formatted(
                cumulativeLow.toPlainString(), cumulativeHigh.toPlainString()));
        if (anyIncomplete) {
            out.append("  ** 단가 미상 후보가 있어 위 누적은 전체 비용이 아니다. 0 이 아니라 "
                    + "'아직 모른다' 이다 — -PcellPrices 로 핀하면 다시 낸다. **\n");
        }
        out.append("══════════════════════════════════════════════════════════════\n");
        return out.toString();
    }

    /** 단계 안에서 후보 하나가 차지하는 몫. "이 후보를 빼면 얼마가 줄어드는가" 를 바로 보게 한다. */
    private static void appendPerCandidate(StringBuilder out, Projection projection) {
        Map<String, List<CellEstimate>> byCandidate = projection.byCandidate();
        if (byCandidate.size() <= 1) {
            return;
        }
        out.append("    후보별 몫:\n");
        byCandidate.forEach((candidate, estimates) -> {
            BigDecimal low = estimates.stream().map(CellEstimate::lowUsd)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal high = estimates.stream()
                    .map(estimate -> estimate.withReasoningUsd().orElse(estimate.highUsd()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean complete = estimates.stream().allMatch(CellEstimate::complete);
            boolean reasoning = estimates.stream().anyMatch(CellEstimate::hasReasoningModel);
            out.append("      %-24s $%s ~ $%s%s%s%n".formatted(candidate,
                    low.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    high.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                    complete ? "" : "  ← 단가 미상 포함",
                    reasoning ? "  ← 추론 토큰 가정 포함, 천장 아님" : ""));
        });
    }

    /** 표본 실행 견적. 파일럿 승인용. escalation 최악까지 포함한 상한을 낸다. */
    static Optional<BigDecimal> pilotUpperBound(Projection projection) {
        if (!projection.complete()) {
            return Optional.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (CellEstimate estimate : projection.estimates()) {
            Optional<BigDecimal> ceiling = estimate.ceilingUsd();
            if (ceiling.isEmpty()) {
                return Optional.empty();
            }
            total = total.add(ceiling.get());
        }
        return Optional.of(total);
    }
}
