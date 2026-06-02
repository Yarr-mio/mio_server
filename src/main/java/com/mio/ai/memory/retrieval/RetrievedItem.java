package com.mio.ai.memory.retrieval;

/**
 * 단일 검색 결과 항목 — FusionRanker 입력.
 */
public record RetrievedItem(
        String id,
        RetrievalSource source,
        String content,
        String sensitivity,   // "normal" | "sensitive" | "restricted"
        double score,
        int rank
) {}
