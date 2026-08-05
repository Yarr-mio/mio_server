package com.mio.ai.plan;

import java.util.List;

/**
 * 계약 검사 결과 (이슈 #303).
 *
 * @param applicable 계약이 적용되는 계획이었는지. {@code false} 는 "위반 없음"이 아니라
 *                   "검사 대상 아님"이다 — 계획되지 않은 턴을 준수로 세면 지표가 왜곡된다
 */
public record ResponseContractResult(
        boolean applicable,
        boolean passed,
        List<String> violations
) {
    public ResponseContractResult {
        violations = violations != null ? List.copyOf(violations) : List.of();
    }

    public static ResponseContractResult notApplicable() {
        return new ResponseContractResult(false, true, List.of());
    }

    public static ResponseContractResult pass() {
        return new ResponseContractResult(true, true, List.of());
    }

    public static ResponseContractResult violated(List<String> violations) {
        return new ResponseContractResult(true, false, violations);
    }

    /** 결정 로그에 남길 값 — 검사 대상이 아니었던 턴과 통과한 턴을 구분한다. */
    public String logValue() {
        if (!applicable) {
            return "NOT_APPLICABLE";
        }
        return passed ? "PASS" : "VIOLATED";
    }
}
