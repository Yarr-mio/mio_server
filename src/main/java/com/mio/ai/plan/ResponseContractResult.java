package com.mio.ai.plan;

import java.util.List;

/**
 * 계약 검사 결과 (이슈 #303).
 *
 * <p>세 가지를 구분한다. 계획 밖 턴을 준수로 세면 준수율이 실제보다 높아 보이고, 검사하지
 * 못한 턴을 준수로 세면 그 사실 자체가 사라진다.
 *
 * @param applicable 계약이 적용되는 계획이었는지
 * @param checked    실제로 검사를 실행했는지. 계약이 있는데 검사하지 못한 턴이 존재한다 —
 *                   사후 검사가 없는 {@code SPECULATIVE} 전달이 그렇다
 */
public record ResponseContractResult(
        boolean applicable,
        boolean checked,
        boolean passed,
        List<String> violations
) {
    public ResponseContractResult {
        violations = violations != null ? List.copyOf(violations) : List.of();
    }

    public static ResponseContractResult notApplicable() {
        return new ResponseContractResult(false, false, true, List.of());
    }

    /** 계약은 있으나 이 전달 경로에는 검사 지점이 없다. */
    public static ResponseContractResult unchecked() {
        return new ResponseContractResult(true, false, true, List.of());
    }

    public static ResponseContractResult pass() {
        return new ResponseContractResult(true, true, true, List.of());
    }

    public static ResponseContractResult violated(List<String> violations) {
        return new ResponseContractResult(true, true, false, violations);
    }

    /** 결정 로그에 남길 값 — 대상 아님·미검사·통과·위반을 모두 구분한다. */
    public String logValue() {
        if (!applicable) {
            return "NOT_APPLICABLE";
        }
        if (!checked) {
            return "UNCHECKED";
        }
        return passed ? "PASS" : "VIOLATED";
    }
}
