package com.mio.session.domain;

import java.util.Arrays;

public enum SummaryStatus {
    PENDING("pending"),
    DONE("done"),
    VIEWED("viewed"),
    FAILED("failed");

    private final String value;

    SummaryStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SummaryStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown summary status: " + value));
    }
}
