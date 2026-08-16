package com.mio.ai.qa;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 셀 C 의 offline reference judge 채점 결과 (로드맵 §11.3).
 *
 * <h2>이 pass 가 온라인 수치에 섞일 수 없는 이유</h2>
 *
 * <p>세 가지가 <b>구조적으로</b> 분리돼 있다.
 *
 * <ol>
 *   <li><b>시점.</b> 온라인 케이스 결과가 전부 확정된 뒤에 시작한다. 지연 측정은 케이스별
 *       {@code totalMs} 로 이미 닫혀 있어 이 pass 가 늘릴 수 있는 값이 없다.</li>
 *   <li><b>원장.</b> 자기 {@link CellTokenLedger} 를 쓴다. 온라인 원가·토큰 합계는 온라인
 *       원장에서만 나오므로 이 pass 의 토큰이 더해질 경로가 없다.</li>
 *   <li><b>태그.</b> 요청에 {@link CellModelRole#OFFLINE_COMPONENT} 가 붙는다. 온라인 어느
 *       역할과도 겹치지 않으므로, 만에 하나 섞여도 사후에 구별된다 —
 *       {@link CellParity} 가 온라인 원장에 이 태그가 0건임을 단언한다.</li>
 * </ol>
 *
 * <h2>무엇을 신호로 내는가</h2>
 *
 * <p>셀 C 의 가설은 "운영비 증가 없이 <b>오류 발견·라벨 품질</b>이 개선되는가" 다. 그래서
 * 두 가지만 낸다.
 *
 * <ul>
 *   <li><b>라벨 이견률</b> — reference 의 독립 판정이 gold 라벨과 갈린 비율. 잠금셋이 1인
 *       라벨이라는 미해결 게이트에 직접 닿는 수치다.</li>
 *   <li><b>미탐 후보</b> — 온라인 경로가 무검사로 내보낸(UNGUARDED) 턴 중 reference 가
 *       위험이라고 본 건. gold 가 CLEAR 라도 여기 걸리면 사람이 다시 볼 대상이다.</li>
 * </ul>
 *
 * <p>둘 다 <b>Go/No-Go 에 들어가지 않는다.</b> 단일 LLM judge 점수로 모델을 고르지 않는다는
 * §11.3 의 원칙 그대로이며, reference 자신도 채점 실패할 수 있어서 실패 건수를 분모에서
 * 지우지 않고 따로 적는다.
 */
record CellReferenceReview(String model, int scored, int failed,
                           long labelDisagreements, List<String> disagreementCaseIds,
                           List<String> unguardedRiskCaseIds,
                           Map<String, Long> referenceLabelCounts,
                           long promptTokens, long completionTokens, int calls,
                           Optional<BigDecimal> costUsd, Duration elapsed) {

    /** 이 절이 온라인 지표와 섞이지 않는다는 사실을 리포트 안에서도 문장으로 남긴다. */
    static final String SEPARATION_NOTE =
            "이 절의 호출·토큰·원가·소요 시간은 별도 원장에서 나온다 — 위의 턴당 원가·p50/p95 "
                    + "어디에도 들어가지 않고, Go/No-Go 계산에도 쓰이지 않는다";

    CellReferenceReview {
        disagreementCaseIds = List.copyOf(disagreementCaseIds);
        unguardedRiskCaseIds = List.copyOf(unguardedRiskCaseIds);
        referenceLabelCounts = Map.copyOf(referenceLabelCounts);
    }

    /** gold 라벨과의 이견률. 채점 성공분만 분모다 — 실패를 "일치" 로 접으면 신호가 사라진다. */
    ReportableRate labelDisagreementRate() {
        return ReportableRate.of("reference-gold 라벨 이견률", labelDisagreements, scored);
    }

    String render() {
        StringBuilder out = new StringBuilder();
        out.append("\n  [offline reference judge — 셀 C 전용, 운영 경로 밖]\n");
        out.append("    ** %s **%n".formatted(SEPARATION_NOTE));
        out.append("    reference 모델    %s%n".formatted(model));
        out.append("    채점 성공/실패    %d건 / %d건  ← 실패를 일치로 접지 않는다%n"
                .formatted(scored, failed));
        out.append("    라벨 이견률       %s%n".formatted(labelDisagreementRate().display()));
        out.append("    reference 라벨 분포 %s%n".formatted(referenceLabelCounts));
        out.append("    미탐 후보(무검사 노출 중 reference 위험 판정) %d건%n"
                .formatted(unguardedRiskCaseIds.size()));
        appendIds(out, "이견 케이스 ID", disagreementCaseIds);
        appendIds(out, "미탐 후보 케이스 ID", unguardedRiskCaseIds);
        out.append("    offline 호출/토큰 %d건 · prompt %d / completion %d%n"
                .formatted(calls, promptTokens, completionTokens));
        out.append("    offline 원가      %s  (온라인 원가와 합산하지 않는다)%n"
                .formatted(CellPricingBook.format(costUsd)));
        out.append("    offline 소요      %d분 %d초%n"
                .formatted(elapsed.toMinutes(), elapsed.toSecondsPart()));
        return out.toString();
    }

    /** 본문은 남기지 않는다 — 아카이브가 잠금 세트 사본이 되면 오염 스캐너가 잡는다. */
    private static void appendIds(StringBuilder out, String title, List<String> ids) {
        out.append("    %s — %d건%n".formatted(title, ids.size()));
        for (int i = 0; i < ids.size(); i += 4) {
            out.append("      ").append(String.join("  ",
                    ids.subList(i, Math.min(i + 4, ids.size())))).append('\n');
        }
    }

    /** manifest 항목. 온라인 항목과 이름이 겹치지 않게 전부 {@code reference_} 접두사를 붙인다. */
    Map<String, String> asManifestFields() {
        return Map.of(
                "reference_model", model,
                "reference_scored", "%d건 (실패 %d건)".formatted(scored, failed),
                "reference_label_disagreement", labelDisagreementRate().display(),
                "reference_unguarded_risk_case_ids",
                unguardedRiskCaseIds.isEmpty() ? "없음" : String.join(" ", unguardedRiskCaseIds),
                "reference_disagreement_case_ids",
                disagreementCaseIds.isEmpty() ? "없음" : String.join(" ", disagreementCaseIds),
                "reference_cost_usd_offline_only",
                "%s (온라인 원가와 별도)".formatted(CellPricingBook.format(costUsd)),
                "reference_separation", SEPARATION_NOTE);
    }
}
