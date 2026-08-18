package com.mio.session.domain;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;

/** 핵심 요약 이후 독립적으로 완료되는 파생 작업의 상태. */
public enum SummaryComponentStatus {
    UNKNOWN("unknown"),
    PENDING("pending"),
    DONE("done"),
    SKIPPED("skipped"),
    FAILED("failed");

    private final String value;

    SummaryComponentStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SummaryComponentStatus fromValue(String value) {
        for (SummaryComponentStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
}
