package com.mio.user.domain;

import java.util.Arrays;

/**
 * 데이터 삭제 요청의 상태 (이슈 #373, 로드맵 §12 P0-6 · §10.1).
 *
 * <p>{@code failed} 를 별도 값으로 두는 것이 핵심이다. 실패를 {@code pending} 으로 되돌리면
 * 영원히 재시도되면서 아무도 문제를 모르고, {@code completed} 로 적으면 지워지지 않은
 * 데이터가 지워진 것으로 기록된다. 개인정보 삭제에서 후자는 특히 위험하다.
 */
public enum DeletionStatus {

    /** 탈퇴 접수. 유예 기간 대기 중. */
    PENDING("pending"),

    /** 하드 삭제 실행 중. */
    IN_PROGRESS("in_progress"),

    /** 모든 저장소에서 제거 완료. */
    COMPLETED("completed"),

    /** 재시도 상한까지 실패. 운영 개입이 필요하다. */
    FAILED("failed");

    private final String value;

    DeletionStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public static DeletionStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown deletion status: " + value));
    }
}
