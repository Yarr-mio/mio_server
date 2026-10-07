package com.mio.ai.qa.correction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 시험 세트의 <b>집계</b>와 규모·분포 경고 (이슈 #554).
 *
 * <p>평가용 세트는 프롬프트를 고치는 쪽이 내용을 보지 않아야 하므로, 검증 결과는 케이스 본문이나
 * id 없이 건수와 분포만 담는다. 이 요약은 그대로 공유해도 세트 내용이 새지 않는다.
 *
 * <p>경고는 실패가 아니다 — 작성 가이드({@code src/test/resources/eval/correction/AUTHORING.md})의 권장
 * 규모보다 모자란다는 알림이다.
 */
record CorrectionEvalSetSummary(String version, int total, int corrections, int controls,
                                Map<String, Integer> byType, Map<String, Integer> byStrength,
                                int adviceRequested, Map<Integer, Integer> byTurnCount,
                                List<String> warnings) {

    static final int MIN_PER_CORRECTION_TYPE = 6;
    static final int MIN_CONTROLS = 20;
    static final int MIN_ADVICE_REQUESTED = 6;

    static CorrectionEvalSetSummary of(CorrectionEvalSet set) {
        Map<String, Integer> byType = new TreeMap<>();
        Map<String, Integer> byStrength = new TreeMap<>();
        Map<Integer, Integer> byTurnCount = new TreeMap<>();
        int corrections = 0;
        int controls = 0;
        int adviceRequested = 0;
        for (CorrectionEvalCase c : set.cases()) {
            byType.merge(c.type(), 1, Integer::sum);
            byTurnCount.merge(c.turns().size(), 1, Integer::sum);
            if (c.correction()) {
                corrections++;
                byStrength.merge(c.strength(), 1, Integer::sum);
            } else {
                controls++;
            }
            if (c.adviceRequested()) {
                adviceRequested++;
            }
        }

        List<String> warnings = new ArrayList<>();
        for (String type : CorrectionEvalCase.CORRECTION_TYPES.stream().sorted().toList()) {
            int count = byType.getOrDefault(type, 0);
            if (count < MIN_PER_CORRECTION_TYPE) {
                warnings.add("정정 종류 %s 가 %d건이다 (권장 %d건 이상)"
                        .formatted(type, count, MIN_PER_CORRECTION_TYPE));
            }
        }
        for (String strength : CorrectionEvalCase.STRENGTHS.stream().sorted().toList()) {
            if (byStrength.getOrDefault(strength, 0) == 0) {
                warnings.add("강도 %s 케이스가 하나도 없다".formatted(strength));
            }
        }
        if (controls < MIN_CONTROLS) {
            warnings.add("대조군이 %d건이다 (권장 %d건 이상)".formatted(controls, MIN_CONTROLS));
        }
        if (adviceRequested < MIN_ADVICE_REQUESTED) {
            warnings.add("조언 요청 대조군이 %d건이다 (권장 %d건 이상)"
                    .formatted(adviceRequested, MIN_ADVICE_REQUESTED));
        }
        if (byTurnCount.size() < 2) {
            warnings.add("대화 길이가 한 가지뿐이다 (3턴과 5턴을 섞는 것을 권장)");
        }
        return new CorrectionEvalSetSummary(set.version(), set.cases().size(), corrections, controls,
                byType, byStrength, adviceRequested, byTurnCount, List.copyOf(warnings));
    }

    String render(String sha256) {
        StringBuilder sb = new StringBuilder();
        sb.append("[correction-eval-set] version=").append(version).append(" 총 ").append(total)
                .append("건 (정정 ").append(corrections).append(" / 대조군 ").append(controls).append(")\n");
        sb.append("  종류별: ").append(byType).append('\n');
        sb.append("  정정 강도별: ").append(byStrength).append('\n');
        sb.append("  조언 요청 대조군: ").append(adviceRequested).append('\n');
        sb.append("  대화 턴 수별: ").append(byTurnCount).append('\n');
        sb.append("  sha256: ").append(sha256).append('\n');
        if (warnings.isEmpty()) {
            sb.append("  경고 없음\n");
        } else {
            warnings.forEach(w -> sb.append("  경고: ").append(w).append('\n'));
        }
        return sb.toString();
    }
}
