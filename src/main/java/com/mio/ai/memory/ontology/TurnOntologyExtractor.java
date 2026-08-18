package com.mio.ai.memory.ontology;

import java.util.UUID;

public interface TurnOntologyExtractor {

    TurnOntologySignal extract(String userMessage, UUID userId, UUID sessionId);
}
