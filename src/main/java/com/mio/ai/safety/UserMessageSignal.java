package com.mio.ai.safety;

public record UserMessageSignal(
        Integer emotionScore,
        String biasType
) {
}
