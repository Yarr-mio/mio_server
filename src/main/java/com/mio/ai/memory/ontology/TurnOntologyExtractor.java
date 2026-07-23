package com.mio.ai.memory.ontology;

public interface TurnOntologyExtractor {

    TurnOntologySignal extract(String userMessage);
}
