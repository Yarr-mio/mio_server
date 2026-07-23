package com.mio.ai.memory.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.memory.working.SessionDelta;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OntologyInterventionFilterTest {

    private final InterventionDefRepository repository = mock(InterventionDefRepository.class);
    private final OntologyInterventionFilter filter = new OntologyInterventionFilter(repository, new ObjectMapper());

    @Test
    void excludesInterventionThatRequiresCalmWhenRiskSignalsAreActive() {
        InterventionDef restructuring = definition("cognitive_restructuring",
                "{\"requires_calm_state\": true, \"high_crisis\": true}");
        when(repository.findAllById(List.of("cognitive_restructuring"))).thenReturn(List.of(restructuring));

        InterventionHints result = filter.filter(
                new InterventionHints(List.of("cognitive_restructuring"), List.of(), null),
                combined(false, true, false, false), sessionDelta(0));

        assertThat(result.suggestedCodes()).isEmpty();
    }

    @Test
    void excludesInterventionAtItsOntologySessionLimit() {
        InterventionDef socratic = definition("socratic_questioning", "{\"session_limit\": 2}");
        when(repository.findAllById(List.of("socratic_questioning"))).thenReturn(List.of(socratic));

        InterventionHints result = filter.filter(
                new InterventionHints(List.of("socratic_questioning"), List.of(), null),
                combined(false, false, false, false), sessionDelta(2));

        assertThat(result.suggestedCodes()).isEmpty();
    }

    @Test
    void preservesKnownEligibleInterventionAndDropsUnknownCode() {
        InterventionDef breathing = definition("breathing_exercise", "{\"high_crisis\": false}");
        when(repository.findAllById(List.of("breathing_exercise", "unknown"))).thenReturn(List.of(breathing));

        InterventionHints result = filter.filter(
                new InterventionHints(List.of("breathing_exercise", "unknown"), List.of("avoid"), "target"),
                combined(false, false, false, false), sessionDelta(0));

        assertThat(result.suggestedCodes()).containsExactly("breathing_exercise");
        assertThat(result.avoidCodes()).containsExactly("avoid");
        assertThat(result.targetDistortionCode()).isEqualTo("target");
    }

    @Test
    void failsClosedWhenOntologyLookupFails() {
        when(repository.findAllById(List.of("breathing_exercise"))).thenThrow(new RuntimeException("db unavailable"));

        InterventionHints result = filter.filter(
                new InterventionHints(List.of("breathing_exercise"), List.of(), null),
                combined(false, false, false, false), sessionDelta(0));

        assertThat(result.suggestedCodes()).isEmpty();
    }

    private InterventionDef definition(String code, String contraindicatedWhen) {
        InterventionDef definition = mock(InterventionDef.class);
        when(definition.getCode()).thenReturn(code);
        when(definition.getContraindicatedWhen()).thenReturn(contraindicatedWhen);
        return definition;
    }

    private CombinedSignal combined(boolean hardCrisis, boolean riskCandidate,
                                    boolean emotionSpike, boolean repetitiveNegative) {
        return new CombinedSignal(SecurityLevel.CLEAN, hardCrisis, riskCandidate, emotionSpike,
                repetitiveNegative, false, false, false, SafetyL1Result.clear(), 0.0);
    }

    private SessionDelta sessionDelta(int socraticCount) {
        return new SessionDelta(socraticCount, "none", Map.of(), 0, java.util.Set.of(), java.util.Set.of());
    }
}
