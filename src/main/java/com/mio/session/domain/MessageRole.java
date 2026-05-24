package com.mio.session.domain;

import java.util.Arrays;

public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MessageRole fromValue(String value) {
        return Arrays.stream(values())
                .filter(role -> role.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown message role: " + value));
    }
}
