package com.mio.ai.judge;

import java.util.List;
import java.util.Set;

public record CbtMetadataResult(
        CbtInterventionState state,
        String completionReason,
        boolean requiresEmotionScore,
        boolean socratic,
        String biasType,
        String reconstructedThought,
        List<CbtSegment> segments
) {
    private static final Set<String> ALLOWED_BIAS_TYPES = Set.of(
            "overgeneralization",
            "catastrophizing",
            "mind_reading",
            "all_or_nothing",
            "self_blame",
            "emotional_reasoning"
    );

    /** API v1.1 {@code segments[].type} 계약이 허용하는 값 (이슈 #549). */
    public static final Set<String> ALLOWED_SEGMENT_TYPES = Set.of(
            "reflection", "validation", "question", "suggestion", "summary");

    /**
     * 응답 문장 단위 세그먼트 (이슈 #549, API v1.1 {@code segments[]}).
     *
     * <p>{@code content}는 분류기(LLM)가 다시 쓴 것이 아니라 실제 전달된 본문의 부분 문자열이어야
     * 한다 — 호출부에서 원문과 대조 검증한다({@code ConversationOrchestrator.buildSegments()}).
     */
    public record CbtSegment(String type, String content) {}

    public static CbtMetadataResult none() {
        return new CbtMetadataResult(CbtInterventionState.NONE, null, false, false, null, null, List.of());
    }

    public boolean shouldCreateEmotionScoreTarget() {
        return state == CbtInterventionState.COMPLETED && requiresEmotionScore;
    }

    public static boolean isAllowedBiasType(String biasType) {
        return biasType != null && ALLOWED_BIAS_TYPES.contains(biasType);
    }
}
