package com.mio.ai.memory.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalPlanTest {

    @Test
    void cbtInterventionPlanIncludesBothActivatedBeliefsAndTriggers() {
        RetrievalPlan plan = RetrievalPlan.cbtIntervention();

        assertThat(plan.sources()).contains(
                RetrievalSource.GRAPH_BELIEF_NEIGH,
                RetrievalSource.GRAPH_TRIGGER
        );
    }
}
