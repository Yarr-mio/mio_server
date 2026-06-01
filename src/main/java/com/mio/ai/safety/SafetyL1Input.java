package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;

import java.util.List;

public record SafetyL1Input(
        String normalizedMessage,
        List<SafetyL1HistoryMessage> recentMessages,
        ModerationResult moderationResult,
        SafetyProfile profile,
        Integer currentEmotionScore,
        String currentBiasType
) {
    public SafetyL1Input(
            String normalizedMessage,
            List<SafetyL1HistoryMessage> recentMessages,
            ModerationResult moderationResult) {
        this(normalizedMessage, recentMessages, moderationResult, null, null, null);
    }

    public SafetyL1Input(
            String normalizedMessage,
            List<SafetyL1HistoryMessage> recentMessages,
            ModerationResult moderationResult,
            SafetyProfile profile) {
        this(normalizedMessage, recentMessages, moderationResult, profile, null, null);
    }
}
