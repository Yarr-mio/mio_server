package com.mio.ai.memory.ontology;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 보조 온톨로지 작업의 executor 포화가 주 대화 요청을 실패시키지 않도록 격리한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReactiveOntologyActivationDispatcher {

    private final ReactiveOntologyActivator activator;

    public void activateBeliefs(UUID userId, UUID sessionId, String normalizedMessage) {
        try {
            activator.activateBeliefs(userId, sessionId, normalizedMessage);
        } catch (TaskRejectedException e) {
            log.warn("Reactive ontology activation queue is full; skipping sessionId={}", sessionId);
        }
    }
}
