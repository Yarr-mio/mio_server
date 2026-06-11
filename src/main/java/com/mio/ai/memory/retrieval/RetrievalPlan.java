package com.mio.ai.memory.retrieval;

import java.util.List;

/**
 * MemoryRetrievalPlanner가 생성하는 결정론적 검색 계획.
 * LLM 미호출.
 */
public record RetrievalPlan(
        List<RetrievalSource> sources,
        int maxK,
        int budgetMs,
        String sensitivityCap   // "normal" | "sensitive" | "restricted"
) {
    public static RetrievalPlan clearLow() {
        return new RetrievalPlan(
                List.of(RetrievalSource.VECTOR_EPISODE,
                        RetrievalSource.SQL_PROFILE,
                        RetrievalSource.SQL_RHYTHM),
                3, 200, "normal"
        );
    }

    public static RetrievalPlan medium() {
        return new RetrievalPlan(
                List.of(RetrievalSource.GRAPH_TRIGGER,
                        RetrievalSource.GRAPH_INTERVENTION_FIT,
                        RetrievalSource.VECTOR_BELIEF,
                        RetrievalSource.SQL_TODO_HISTORY),
                3, 300, "sensitive"
        );
    }

    public static RetrievalPlan high() {
        return new RetrievalPlan(
                List.of(RetrievalSource.GRAPH_TRIGGER,
                        RetrievalSource.SQL_RHYTHM,
                        RetrievalSource.SQL_RECENT_RISK),
                3, 200, "sensitive"
        );
    }

    public static RetrievalPlan cbtIntervention() {
        return new RetrievalPlan(
                List.of(RetrievalSource.GRAPH_BELIEF_NEIGH,
                        RetrievalSource.GRAPH_INTERVENTION_FIT,
                        RetrievalSource.VECTOR_BELIEF),
                3, 300, "sensitive"
        );
    }

    public static RetrievalPlan newUser() {
        return new RetrievalPlan(
                List.of(RetrievalSource.SQL_PROFILE,
                        RetrievalSource.VECTOR_EPISODE),
                2, 150, "normal"
        );
    }
}
