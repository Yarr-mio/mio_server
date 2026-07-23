package com.mio.ai.memory.retrieval;

public enum RetrievalSource {
    VECTOR_EPISODE,        // pgvector: session_summaries.episode_emb
    LEXICAL_EPISODE,       // PostgreSQL FTS: session_summaries.summary_text
    VECTOR_BELIEF,         // pgvector: user_beliefs (belief embedding)
    GRAPH_TRIGGER,         // GIN: session_summaries.trigger_tags
    GRAPH_INTERVENTION_FIT,// intervention_outcomes + behavior_tasks
    GRAPH_BELIEF_NEIGH,    // belief_evidence 이웃 신념
    SQL_PROFILE,           // user_beliefs + cbt_patterns 요약
    SQL_RHYTHM,            // emotional_states 최근 24h
    SQL_RECENT_RISK,       // safety_risk_daily 최근 7일
    SQL_TODO_HISTORY       // behavior_tasks 최근 완료/실패 이력
}
