package com.mio.todo.dto;

import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;

public record TodoCheckinResponse(
        String status,

        Integer beforeEmotion,

        Integer afterEmotion,

        String characterReaction
) {
    private static final String REACTION_COMPLETED = "잘했어! 작은 것부터 하나씩 해나가는 게 진짜 대단한 거야 🎉";
    private static final String REACTION_SKIPPED = "괜찮아, 다음에 또 도전해봐요!";

    public static TodoCheckinResponse from(BehaviorTask task) {
        String reaction = TaskStatus.COMPLETED == task.getStatus()
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
