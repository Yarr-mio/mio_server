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
 *   <li><b>escalation 재시도는 포함되지 않는다.</b> 스텁 응답은 계약 위반을 일으키지 않아
 *       셀 D·E 의 재생성이 발동하지 않는다. 그만큼 셀 D·E 는 과소 추정이다.</li>
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

    private CellCostEstimator() {
    }

    /**
     * 셀 하나의 견적.
     *
     * @param knownUsd     단가가 등록된 모델들의 비용 합
     * @param unknownModels 단가를 모르는 모델과 그 토큰 볼륨
     */
    record CellEstimate(BenchmarkCell cell, int cases, int llmCalls,
                        long promptTokens, long completionTokens,
                        BigDecimal knownUsd, Map<String, long[]> unknownModels,
                        Map<String, long[]> tokensByModel, Map<String, Long> callsByComponent) {

        boolean complete() {
            return unknownModels.isEmpty();
        }

        /** 토큰 추정 오차를 반영한 하한. */
        BigDecimal lowUsd() {
            return scaled(CellTokenEstimator.LOWER_MULTIPLIER);
        }

        /** 토큰 추정 오차를 반영한 상한. 승인 판단은 이 값으로 하는 편이 안전하다. */
        BigDecimal highUsd() {
            return scaled(CellTokenEstimator.UPPER_MULTIPLIER);
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
    }

    /** 셀 목록을 잠금 세트 전량 기준으로 견적낸다. */
    static Projection project(List<BenchmarkCell> cells, List<LockedEvalSet.LockedCase> cases) {
        List<CellEstimate> estimates = new ArrayList<>();
        CellPricingBook pricing = null;
        for (BenchmarkCell cell : cells) {
            CellModelRegistry registry = CellModelRegistry.resolveForEstimate(cell, Map.of());
            pricing = registry.pricing();
            estimates.add(estimate(cell, registry, cases));
        }
        return new Projection(estimates,
                pricing != null ? pricing : CellPricingBook.load(Map.of(), null), cases.size());
    }

    private static CellEstimate estimate(BenchmarkCell cell, CellModelRegistry registry,
                                         List<LockedEvalSet.LockedCase> cases) {
        CellRunner.Result result;
        try {
            result = CellRunner.stubbed(cell, registry).run(cases, false);
        } catch (Exception e) {
            throw new IllegalStateException("셀 %s 견적 실행 실패".formatted(cell.name()), e);
        }

        Map<String, long[]> tokensByModel = new TreeMap<>();
        Map<String, long[]> unknown = new TreeMap<>();
        Map<String, Long> callsByComponent = new TreeMap<>();
        BigDecimal known = BigDecimal.ZERO;
        for (CellTokenLedger.Call call : result.ledger().calls()) {
            callsByComponent.merge(call.component(), 1L, Long::sum);
            long[] tokens = tokensByModel.computeIfAbsent(call.model(), k -> new long[]{0, 0, 0});
            tokens[0] += call.promptTokens();
            tokens[1] += call.completionTokens();
            tokens[2]++;
            if (call.priced()) {
                known = known.add(call.costUsd());
            } else {
                long[] u = unknown.computeIfAbsent(call.model(), k -> new long[]{0, 0, 0});
                u[0] += call.promptTokens();
                u[1] += call.completionTokens();
                u[2]++;
            }
        }
        return new CellEstimate(cell, cases.size(), result.ledger().calls().size(),
                result.ledger().promptTokens(), result.ledger().completionTokens(),
                known.setScale(4, RoundingMode.HALF_UP), unknown, tokensByModel,
                callsByComponent);
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

        out.append("\n  %-4s %6s %10s %12s %14s %s%n"
                .formatted("셀", "호출", "prompt", "completion", "추정 USD", "비고"));
        projection.estimates().forEach(estimate -> out.append("  %-4s %6d %10d %12d %14s %s%n"
                .formatted(estimate.cell().name(), estimate.llmCalls(), estimate.promptTokens(),
                        estimate.completionTokens(),
                        "$%s~$%s%s".formatted(estimate.lowUsd().toPlainString(),
                                estimate.highUsd().toPlainString(),
                                estimate.complete() ? "" : " (일부)"),
                        estimate.complete() ? ""
                                : "단가 미상 모델 포함 — 금액은 등록분만, 아래 민감도표 참조")));

        out.append("\n  [역할별 호출 수 — §11.3 '호출 수']\n");
        projection.estimates().forEach(estimate -> out.append("    %-4s %s%n"
                .formatted(estimate.cell().name(), estimate.callsByComponent())));

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

        appendSensitivity(out, projection);
        appendAssumptions(out);
        out.append("══════════════════════════════════════════════════════════════\n");
        return out.toString();
    }

    /** 단가 미상 모델의 비용을 현행 {@code gpt-4o} 단가의 배수 가정으로 펼친다. */
    private static void appendSensitivity(StringBuilder out, Projection projection) {
        Map<BenchmarkCell, Map<String, long[]>> unknown = new LinkedHashMap<>();
        projection.estimates().stream()
                .filter(e -> !e.complete())
                .forEach(e -> unknown.put(e.cell(), e.unknownModels()));
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
        unknown.forEach((cell, models) -> models.forEach((model, tokens) -> {
            out.append("    셀 %s · %s · prompt %d / completion %d%n"
                    .formatted(cell.name(), model, tokens[0], tokens[1]));
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
        out.append("    · escalation 재시도는 발동하지 않는다 — 셀 D·E 는 그만큼 과소\n");
        out.append("    · L0 Moderation 호출은 하네스가 하지 않으므로 비용에 없다\n");
        out.append("    · 재시도(429)·부분 실패의 추가 비용은 포함하지 않는다\n");
        out.append("    · 실행 후에는 이 추정을 쓰지 않는다 — 제공자가 보고한 실측 토큰이 아카이브에 남는다\n");
    }

    /** 표본 실행 견적. 파일럿 승인용. */
    static Optional<BigDecimal> pilotUpperBound(Projection projection) {
        return projection.complete()
                ? Optional.of(projection.estimates().stream().map(CellEstimate::highUsd)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                : Optional.empty();
    }
}
