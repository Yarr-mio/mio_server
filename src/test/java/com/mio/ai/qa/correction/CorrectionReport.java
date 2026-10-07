package com.mio.ai.qa.correction;

import com.mio.ai.qa.correction.CorrectionEvalRunner.ScoredSample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 측정 결과의 집계와 리포트 (이슈 #552).
 *
 * <h2>분모 규칙</h2>
 *
 * <p>생성 실패·채점 실패·잘림은 <b>품질 실패가 아니라 실행 실패</b>다. 통과율의 분모는 "채점까지
 * 끝난 표본"이고, 실패는 따로 센다. 섞으면 모델 비교가 네트워크 비교가 된다 (기존 셀 벤치마크와 같은 규칙).
 *
 * <h2>눈가림</h2>
 *
 * <p>{@link Detail#SUMMARY} 는 집계만 담는다 — 케이스 id·응답 본문은 없다. 평가용 세트는 이 형식으로만
 * 공유한다. {@link Detail#DETAIL} 은 케이스 id 별 통과 횟수까지 담으므로 튜닝용 세트에만 쓴다.
 * 어느 쪽도 응답 본문은 출력하지 않는다 (본문은 로컬 아카이브에만 있다).
 */
final class CorrectionReport {

    enum Detail { SUMMARY, DETAIL }

    /** 기존 셀 벤치마크의 하위 그룹 하한(minSubgroupN=30) 관례. 미만이면 "참고용"으로 표시한다. */
    static final int REFERENCE_ONLY_BELOW = 30;

    /** 기준선/후보 비교에서 항목별로 보여 줄 핵심 항목. */
    private static final List<String> KEY_ITEMS = List.of(
            CorrectionVerdict.ACKNOWLEDGED, CorrectionVerdict.NEW_GENERIC_ADVICE, CorrectionVerdict.RESTATED);

    private CorrectionReport() {}

    // ── 집계 ──────────────────────────────────────────────────────

    record Tally(int judged, int passed) {
        Tally plus(boolean pass) {
            return new Tally(judged + 1, passed + (pass ? 1 : 0));
        }

        Double rate() {
            return judged == 0 ? null : (double) passed / judged;
        }
    }

    static final class GroupStats {
        final String mode;
        final String arm;
        int total;
        int generationFailed;
        int truncated;
        int judgeFailed;
        Tally correction = new Tally(0, 0);
        Tally control = new Tally(0, 0);
        final Map<String, Integer> correctionItemTrue = new LinkedHashMap<>();
        final Map<String, Integer> controlItemTrue = new LinkedHashMap<>();
        final Map<String, Tally> byType = new TreeMap<>();
        final Map<String, Tally> byStrength = new TreeMap<>();
        int correctionResponses;
        int correctionWithQuestion;
        int correctionWithinLength;
        final Map<String, Tally> perCorrectionCase = new TreeMap<>();
        final Map<String, Tally> perControlCase = new TreeMap<>();

        GroupStats(String mode, String arm) {
            this.mode = mode;
            this.arm = arm;
        }

        int judged() {
            return correction.judged() + control.judged();
        }

        /** 반복 표본이 엇갈린 정정 케이스 수 — 같은 입력의 응답이 매번 달라진다는 직접 증거다. */
        long unstableCorrectionCases() {
            return perCorrectionCase.values().stream()
                    .filter(t -> t.judged() >= 2 && t.passed() > 0 && t.passed() < t.judged()).count();
        }

        /** 케이스별 통과 비율의 평균 (반복 평균). 표본 수가 케이스마다 달라도 케이스를 동등하게 본다. */
        Double meanCasePassRate() {
            return perCorrectionCase.values().stream().filter(t -> t.judged() > 0)
                    .mapToDouble(t -> (double) t.passed() / t.judged()).average()
                    .stream().boxed().findFirst().orElse(null);
        }
    }

    static List<GroupStats> aggregate(CorrectionEvalRunner.Result result) {
        Map<String, CorrectionEvalCase> cases = new LinkedHashMap<>();
        result.set().cases().forEach(c -> cases.put(c.id(), c));

        Map<String, GroupStats> groups = new LinkedHashMap<>();
        for (ScoredSample scored : result.samples()) {
            String arm = scored.sample().arm().name();
            GroupStats stats = groups.computeIfAbsent(scored.mode() + "|" + arm,
                    key -> new GroupStats(scored.mode(), arm));
            stats.total++;
            if (scored.sample().failed()) {
                stats.generationFailed++;
                continue;
            }
            if (scored.sample().truncated()) {
                stats.truncated++;
            }
            CorrectionEvalCase evalCase = cases.get(scored.sample().caseId());
            CorrectionVerdict verdict = scored.verdict();
            if (evalCase.correction()) {
                stats.correctionResponses++;
                if (scored.shape().questions() > 0) {
                    stats.correctionWithQuestion++;
                }
                if (scored.shape().withinPersonaLength()) {
                    stats.correctionWithinLength++;
                }
            }
            if (verdict == null || verdict.judgeFailed()) {
                stats.judgeFailed++;
                continue;
            }
            boolean pass = verdict.passed();
            if (evalCase.correction()) {
                stats.correction = stats.correction.plus(pass);
                stats.byType.merge(evalCase.type(), new Tally(1, pass ? 1 : 0), CorrectionReport::merge);
                stats.byStrength.merge(evalCase.strength(), new Tally(1, pass ? 1 : 0), CorrectionReport::merge);
                stats.perCorrectionCase.merge(evalCase.id(), new Tally(1, pass ? 1 : 0), CorrectionReport::merge);
                countItems(stats.correctionItemTrue, verdict);
            } else {
                stats.control = stats.control.plus(pass);
                stats.perControlCase.merge(evalCase.id(), new Tally(1, pass ? 1 : 0), CorrectionReport::merge);
                countItems(stats.controlItemTrue, verdict);
            }
        }
        return List.copyOf(groups.values());
    }

    private static Tally merge(Tally a, Tally b) {
        return new Tally(a.judged() + b.judged(), a.passed() + b.passed());
    }

    private static void countItems(Map<String, Integer> target, CorrectionVerdict verdict) {
        verdict.items().forEach((item, value) -> target.merge(item, value ? 1 : 0, Integer::sum));
    }

    /** 전체 표본 중 생성 호출이 실패한 비율. */
    static double generationFailureShare(CorrectionEvalRunner.Result result) {
        long failed = result.samples().stream().filter(s -> s.sample().failed()).count();
        return result.samples().isEmpty() ? 0 : (double) failed / result.samples().size();
    }

    /** 생성에 성공한 표본 중 채점이 실패한 비율. */
    static double judgeFailureShare(CorrectionEvalRunner.Result result) {
        long generated = result.samples().stream().filter(s -> !s.sample().failed()).count();
        long failed = result.samples().stream()
                .filter(s -> !s.sample().failed() && (s.verdict() == null || s.verdict().judgeFailed())).count();
        return generated == 0 ? 0 : (double) failed / generated;
    }

    // ── 렌더링 ────────────────────────────────────────────────────

    static String render(CorrectionEvalRunner.Result result, CorrectionCostLedger ledger, Detail detail) {
        CorrectionRunIdentity id = result.identity();
        List<GroupStats> groups = aggregate(result);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "%n[correction-eval] run_id=%s · 세트 %s (sha256 %s) · 케이스 %d건 × 반복 %d%n",
                id.runId(), id.setVersion(), id.setSha256(), result.set().cases().size(), id.repeats()));
        sb.append(String.format(Locale.ROOT, "  생성 모델=%s · 채점 모델=%s · 기준표=%s · 리포트=%s%n",
                id.generationModel(), id.judgeModel(), id.rubricVersion(), detail));
        sb.append(String.format(Locale.ROOT, "  후보 블록 지문=%s%n", CorrectionPromptArm.blockFingerprint()));
        sb.append("  분모: 채점까지 끝난 표본. 생성 실패·채점 실패·잘림은 품질 실패가 아니라 실행 실패로 따로 센다.\n");
        if (result.budgetStopped()) {
            sb.append("  경고: 비용 상한에 닿아 실행을 중단했다 — 이후 표본은 호출하지 않았고 생성 실패로 표시된다. "
                    + "이 실행은 인용할 수 없다.\n");
        }

        for (GroupStats g : groups) {
            renderGroup(sb, g, detail);
        }
        renderComparison(sb, groups);
        renderCost(sb, ledger);
        return sb.toString();
    }

    private static void renderGroup(StringBuilder sb, GroupStats g, Detail detail) {
        sb.append(String.format(Locale.ROOT, "%n── 모드 %s · 팔 %s ──%n", g.mode, g.arm));
        sb.append(String.format(Locale.ROOT,
                "  표본 %d · 채점 완료 %d · 생성 실패 %d · 채점 실패 %d · 잘림 %d%n",
                g.total, g.judged(), g.generationFailed, g.judgeFailed, g.truncated));
        sb.append("  정정 케이스 통과율: ").append(rate(g.correction)).append('\n');
        appendItems(sb, "    항목별 '예' 비율", g.correctionItemTrue, g.correction.judged());
        sb.append("    종류별: ").append(tallies(g.byType)).append('\n');
        sb.append("    강도별: ").append(tallies(g.byStrength)).append('\n');
        sb.append(String.format(Locale.ROOT, "    물음표 포함 응답: %s · 2~4문장 범위: %s%n",
                fraction(g.correctionWithQuestion, g.correctionResponses),
                fraction(g.correctionWithinLength, g.correctionResponses)));
        sb.append(String.format(Locale.ROOT, "    케이스별 통과 비율 평균: %s · 반복 표본이 엇갈린 케이스: %d개%n",
                g.meanCasePassRate() == null ? "미보고" : pct(g.meanCasePassRate()), g.unstableCorrectionCases()));
        sb.append("  대조군(정정 아님) 통과율: ").append(rate(g.control)).append('\n');
        appendItems(sb, "    항목별 '예' 비율(낮을수록 좋음)", g.controlItemTrue, g.control.judged());
        if (g.control.judged() > 0) {
            sb.append("    ※ '어색한 재진술'은 참고 지표이며 통과 조건이 아니다 (사람 검증 일치율 31%).\n");
        }

        if (detail == Detail.DETAIL) {
            sb.append("  [상세] 정정 케이스별 통과:\n");
            g.perCorrectionCase.forEach((id, t) ->
                    sb.append(String.format(Locale.ROOT, "    %s: %d/%d%n", id, t.passed(), t.judged())));
            sb.append("  [상세] 통과하지 못한 대조군 케이스:\n");
            g.perControlCase.forEach((id, t) -> {
                if (t.passed() < t.judged()) {
                    sb.append(String.format(Locale.ROOT, "    %s: %d/%d%n", id, t.passed(), t.judged()));
                }
            });
        }
    }

    private static void renderComparison(StringBuilder sb, List<GroupStats> groups) {
        List<String> modes = groups.stream().map(g -> g.mode).distinct().toList();
        boolean header = false;
        for (String mode : modes) {
            GroupStats baseline = find(groups, mode, CorrectionPromptArm.BASELINE.name());
            GroupStats candidate = find(groups, mode, CorrectionPromptArm.WITH_CORRECTION_BLOCK.name());
            if (baseline == null || candidate == null) {
                continue;
            }
            if (!header) {
                sb.append(String.format(Locale.ROOT, "%n── 후보 − 기준선 (설명용 차이, 유의성 검정 아님) ──%n"));
                header = true;
            }
            sb.append(String.format(Locale.ROOT, "  모드 %s: 정정 통과율 %s · 대조군 통과율 %s%n", mode,
                    delta(baseline.correction, candidate.correction), delta(baseline.control, candidate.control)));
            for (String item : KEY_ITEMS) {
                sb.append(String.format(Locale.ROOT, "    %s '예' 비율 %s%n", item,
                        deltaItem(baseline, candidate, item)));
            }
        }
        if (header) {
            sb.append("  이 차이가 우연인지는 기준선을 두 번 따로 잰 결과(흔들림 크기)와 비교해서 판단한다.\n");
        }
    }

    private static void renderCost(StringBuilder sb, CorrectionCostLedger ledger) {
        if (ledger == null) {
            return;
        }
        sb.append(String.format(Locale.ROOT, "%n── 실제 비용 (제공자가 보고한 토큰 기준) ──%n"));
        ledger.byComponent().forEach((component, t) -> sb.append(String.format(Locale.ROOT,
                "  %s: 호출 %d · 입력 %d · 출력 %d · $%s%s%n", component, t.calls(), t.promptTokens(),
                t.completionTokens(), t.costUsd().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString(),
                t.unpriced() > 0 ? " · 단가 미등록 호출 " + t.unpriced() + "건 제외" : "")));
        CorrectionCostLedger.Totals total = ledger.total();
        sb.append(String.format(Locale.ROOT, "  합계: $%s%n",
                total.costUsd().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString()));
    }

    // ── 표기 도우미 ───────────────────────────────────────────────

    private static GroupStats find(List<GroupStats> groups, String mode, String arm) {
        return groups.stream().filter(g -> g.mode.equals(mode) && g.arm.equals(arm)).findFirst().orElse(null);
    }

    private static String rate(Tally tally) {
        return tally.judged() == 0 ? "미보고 (n=0)" : fraction(tally.passed(), tally.judged());
    }

    private static String fraction(int part, int whole) {
        if (whole == 0) {
            return "미보고 (n=0)";
        }
        String base = String.format(Locale.ROOT, "%s (%d/%d)", pct((double) part / whole), part, whole);
        return whole < REFERENCE_ONLY_BELOW ? base + " 참고용" : base;
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String tallies(Map<String, Tally> map) {
        if (map.isEmpty()) {
            return "없음";
        }
        List<String> parts = new ArrayList<>();
        map.forEach((key, t) -> parts.add(key + " " + pct((double) t.passed() / t.judged())
                + " (" + t.passed() + "/" + t.judged() + (t.judged() < REFERENCE_ONLY_BELOW ? " 참고용" : "") + ")"));
        return String.join(", ", parts);
    }

    private static void appendItems(StringBuilder sb, String title, Map<String, Integer> trueCounts, int judged) {
        if (judged == 0) {
            return;
        }
        List<String> parts = new ArrayList<>();
        trueCounts.forEach((item, count) -> parts.add(item + " " + pct((double) count / judged)));
        sb.append(title).append(": ").append(String.join(", ", parts)).append('\n');
    }

    private static String delta(Tally baseline, Tally candidate) {
        if (baseline.judged() == 0 || candidate.judged() == 0) {
            return "미보고";
        }
        return String.format(Locale.ROOT, "%s → %s (%+.1f%%p)", pct(baseline.rate()), pct(candidate.rate()),
                (candidate.rate() - baseline.rate()) * 100);
    }

    private static String deltaItem(GroupStats baseline, GroupStats candidate, String item) {
        int bJudged = baseline.correction.judged();
        int cJudged = candidate.correction.judged();
        if (bJudged == 0 || cJudged == 0) {
            return "미보고";
        }
        double b = (double) baseline.correctionItemTrue.getOrDefault(item, 0) / bJudged;
        double c = (double) candidate.correctionItemTrue.getOrDefault(item, 0) / cJudged;
        return String.format(Locale.ROOT, "%s → %s (%+.1f%%p)", pct(b), pct(c), (c - b) * 100);
    }
}
