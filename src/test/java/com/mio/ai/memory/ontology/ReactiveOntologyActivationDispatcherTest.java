package com.mio.ai.memory.ontology;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ReactiveOntologyActivationDispatcherTest {

    @Test
    void ignoresRejectedBackgroundActivationWithoutFailingConversation() {
        ReactiveOntologyActivator activator = mock(ReactiveOntologyActivator.class);
        ReactiveOntologyActivationDispatcher dispatcher = new ReactiveOntologyActivationDispatcher(activator);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        doThrow(new TaskRejectedException("queue full"))
                .when(activator).activateBeliefs(userId, sessionId, "업무 압박이 너무 커");

        assertThatCode(() -> dispatcher.activateBeliefs(userId, sessionId, "업무 압박이 너무 커"))
                .doesNotThrowAnyException();
    }
}
