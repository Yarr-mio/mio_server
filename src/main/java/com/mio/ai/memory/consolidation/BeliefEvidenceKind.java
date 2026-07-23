package com.mio.ai.memory.consolidation;

import java.util.Arrays;
import java.util.Optional;

/** 신념에 대한 현재 사고의 명시적 증거 방향. */
public enum BeliefEvidenceKind {
    SUPPORT,
    CONTRADICT,
    REFRAME;

    public static Optional<BeliefEvidenceKind> from(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(kind -> kind.name().equalsIgnoreCase(value))
                .findFirst();
    }
}
