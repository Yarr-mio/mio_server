package com.mio.ai.memory.ontology;

import com.mio.ai.policy.InterventionHints;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OntologyRelationExpanderTest {

    private final CbtDistortionDefRepository repository = mock(CbtDistortionDefRepository.class);
    private final OntologyRelationExpander expander = new OntologyRelationExpander(repository);

    @Test
    void expandsOnlyRegisteredCooccurringCodesForVerifiedCurrentDistortion() {
        CbtDistortionDef current = definition(List.of("mind_reading", "unknown"), List.of());
        when(repository.findById("catastrophizing")).thenReturn(Optional.of(current));
        when(repository.findCodesByCodeIn(Set.of("mind_reading", "unknown"))).thenReturn(Set.of("mind_reading"));

        Set<String> expanded = expander.expandCooccurringCodes("catastrophizing");

        assertThat(expanded).containsExactly("mind_reading");
        verify(repository).findCodesByCodeIn(Set.of("mind_reading", "unknown"));
    }

    @Test
    void unknownCurrentDistortionDoesNotExpandOrWriteAnyDiagnosis() {
        when(repository.findById("hallucinated_code")).thenReturn(Optional.empty());

        assertThat(expander.expandCooccurringCodes("hallucinated_code")).isEmpty();
        verify(repository, never()).findCodesByCodeIn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reranksOnlyExistingPolicyApprovedHintsByRecommendedActions() {
        CbtDistortionDef current = definition(List.of(), List.of("breathing_exercise"));
        when(repository.findById("catastrophizing")).thenReturn(Optional.of(current));

        InterventionHints result = expander.rerankApprovedHints(
                new InterventionHints(List.of("cognitive_restructuring", "breathing_exercise"),
                        List.of("avoid"), "catastrophizing"),
                "catastrophizing");

        assertThat(result.suggestedCodes()).containsExactly("breathing_exercise");
        assertThat(result.avoidCodes()).containsExactly("avoid");
    }

    @Test
    void retainsApprovedHintsWhenOntologyHasNoMatchingRecommendedAction() {
        CbtDistortionDef current = definition(List.of(), List.of("decatastrophizing"));
        when(repository.findById("catastrophizing")).thenReturn(Optional.of(current));
        InterventionHints original = new InterventionHints(List.of("cognitive_restructuring"), List.of(), null);

        assertThat(expander.rerankApprovedHints(original, "catastrophizing"))
                .isEqualTo(original);
    }

    @Test
    void doesNotCreateHintsWhenPolicyProvidedNone() {
        InterventionHints empty = InterventionHints.empty();

        assertThat(expander.rerankApprovedHints(empty, "catastrophizing")).isEqualTo(empty);
        verifyNoInteractions(repository);
    }

    private CbtDistortionDef definition(List<String> cooccurring, List<String> actions) {
        CbtDistortionDef definition = mock(CbtDistortionDef.class);
        when(definition.getCooccurCodes()).thenReturn(cooccurring);
        when(definition.getRecommendedActions()).thenReturn(actions);
        return definition;
    }
}
