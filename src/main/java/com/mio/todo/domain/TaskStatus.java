package com.mio.todo.domain;

import java.util.Arrays;

public enum TaskStatus {
    SUGGESTED("suggested"),
    COMPLETED("completed"),
    SKIPPED("skipped"),
    EXPIRED("expired");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TaskStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown task status: " + value));
    }
}
