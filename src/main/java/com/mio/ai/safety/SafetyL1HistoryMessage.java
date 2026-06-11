package com.mio.ai.safety;

public record SafetyL1HistoryMessage(
        String normalizedMessage,
        Integer emotionScore,
        String biasType
) {
}
