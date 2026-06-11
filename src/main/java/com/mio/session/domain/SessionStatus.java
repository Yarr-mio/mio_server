package com.mio.session.domain;

import java.util.Arrays;

public enum SessionStatus {
    ACTIVE("active"),
    ENDED("ended");

    private final String value;

    SessionStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SessionStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown session status: " + value));
    }
}
