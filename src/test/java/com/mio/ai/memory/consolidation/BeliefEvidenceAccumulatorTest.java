package com.mio.ai.memory.consolidation;

import com.mio.ai.memory.episodic.BeliefEvidence;
import com.mio.ai.memory.episodic.BeliefEvidenceRepository;
import com.mio.ai.memory.episodic.Thought;
import com.mio.ai.memory.episodic.UserBelief;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BeliefEvidenceAccumulatorTest {

    @Test
    void recordsThoughtAsTheEvidenceEdgeWithoutChangingCountsForReframe() {
        UserBeliefRepository beliefRepository = mock(UserBeliefRepository.class);
        BeliefEvidenceRepository evidenceRepository = mock(BeliefEvidenceRepository.class);
        BeliefEvidenceAccumulator accumulator = new BeliefEvidenceAccumulator(beliefRepository, evidenceRepository);
        UserBelief belief = mock(UserBelief.class);
        Thought thought = mock(Thought.class);

        accumulator.accumulate(belief, BeliefEvidenceKind.REFRAME, UUID.randomUUID(), thought);

        ArgumentCaptor<BeliefEvidence> evidence = ArgumentCaptor.forClass(BeliefEvidence.class);
        verify(evidenceRepository).save(evidence.capture());
        assertThat(evidence.getValue().getThought()).isSameAs(thought);
        verify(belief, never()).addSupport(1.0);
        verify(belief, never()).addContradict(1.0);
    }
}
