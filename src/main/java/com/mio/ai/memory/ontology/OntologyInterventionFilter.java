package com.mio.ai.memory.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.safety.CombinedSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Applies ontology-declared intervention constraints before hints reach the prompt. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OntologyInterventionFilter {

    private final InterventionDefRepository interventionDefRepository;
    private final ObjectMapper objectMapper;

    public InterventionHints filter(InterventionHints hints, CombinedSignal combined, SessionDelta sessionDelta) {
        if (hints == null || hints.suggestedCodes() == null || hints.suggestedCodes().isEmpty()) {
            return InterventionHints.empty();
        }

        try {
            Map<String, InterventionDef> definitions = definitionsByCode(hints.suggestedCodes());
            List<String> eligible = hints.suggestedCodes().stream()
                    .filter(code -> isEligible(definitions.get(code), combined, sessionDelta))
                    .toList();
            return new InterventionHints(eligible, hints.avoidCodes(), hints.targetDistortionCode());
        } catch (Exception e) {
            log.warn("Ontology intervention lookup failed; omitting intervention hints", e);
            return InterventionHints.empty();
        }
    }

    private Map<String, InterventionDef> definitionsByCode(List<String> codes) {
        Map<String, InterventionDef> definitions = new HashMap<>();
        interventionDefRepository.findAllById(codes)
                .forEach(definition -> definitions.put(definition.getCode(), definition));
        return definitions;
    }

    private boolean isEligible(InterventionDef definition, CombinedSignal combined, SessionDelta sessionDelta) {
        if (definition == null) {
            return false;
        }
        try {
            JsonNode constraints = objectMapper.readTree(definition.getContraindicatedWhen());
            if (constraints.path("high_crisis").asBoolean(false) && combined.hardCrisis()) {
                return false;
            }
            if (constraints.path("requires_calm_state").asBoolean(false) && !isCalm(combined)) {
                return false;
            }
            int sessionLimit = constraints.path("session_limit").asInt(-1);
            return sessionLimit < 0 || sessionDelta == null || sessionDelta.socraticQuestionsUsed() < sessionLimit;
        } catch (Exception e) {
            log.warn("Invalid intervention constraints for code={}; omitting hint", definition.getCode(), e);
            return false;
        }
    }

    private boolean isCalm(CombinedSignal combined) {
        return !combined.hardCrisis()
                && !combined.riskCandidate()
                && !combined.emotionSpike()
                && !combined.repetitiveNegative()
                && !combined.dependencyHint()
                && !combined.l0Flagged();
    }
}
