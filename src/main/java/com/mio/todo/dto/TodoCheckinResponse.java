package com.mio.todo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.todo.domain.BehaviorTask;

public record TodoCheckinResponse(
        String status,

        @JsonProperty("before_emotion")
        Integer beforeEmotion,

        @JsonProperty("after_emotion")
        Integer afterEmotion,

        @JsonProperty("character_reaction")
        String characterReaction
) {
    private static final String REACTION_COMPLETED = "잘했어! 작은 것부터 하나씩 해나가는 게 진짜 대단한 거야 🎉";
    private static final String REACTION_SKIPPED = "괜찮아, 다음에 또 도전해봐요!";

    public static TodoCheckinResponse from(BehaviorTask task) {
        String reaction = "completed".equals(task.getStatus().value())
                ? REACTION_COMPLETED
                : REACTION_SKIPPED;
        return new TodoCheckinResponse(
                task.getStatus().value(),
                task.getBeforeEmotion(),
                task.getAfterEmotion(),
                reaction
        );
    }
}
