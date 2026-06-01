package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.session.domain.Message;

import java.util.List;

public record SafetyL1Input(
        String normalizedMessage,
        List<Message> recentMessages,
        ModerationResult moderationResult
) {
}
