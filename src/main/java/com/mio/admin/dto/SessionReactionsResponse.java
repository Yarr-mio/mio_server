package com.mio.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

/**
 * 세션 반응 신호 조회 응답 (이슈 #475, 개선안 문서 §4.2).
 *
 * <p>검증된 치료 효과나 사용자 가치의 직접 증거가 아니라 모델 추정치·제품 반응 신호다.
 * {@code emotionTrend}는 모델이 추정한 신호이지 검증된 임상 결과가 아니다.
 */
public record SessionReactionsResponse(
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("todo_reaction_counts") TodoReactionCounts todoReactionCounts,
        @JsonProperty("disliked_patterns") List<String> dislikedPatterns,
        @JsonProperty("character_affinity_score") Double characterAffinityScore,
        @JsonProperty("cbt_completion_reason") String cbtCompletionReason,
        @JsonProperty("emotion_trend") EmotionTrend emotionTrend,
        @JsonProperty("summary_viewed") boolean summaryViewed,
        @JsonProperty("notified_before_session") Boolean notifiedBeforeSession
) {
    public record TodoReactionCounts(
            @JsonProperty("positive") long positive,
            @JsonProperty("negative") long negative,
            @JsonProperty("neutral") long neutral
    ) {
    }

    /** startScore/endScore/delta 전부 null이면 이 세션에 감정점수가 기록된 메시지가 없다는 뜻이다. */
    public record EmotionTrend(
            @JsonProperty("start_score") Integer startScore,
            @JsonProperty("end_score") Integer endScore,
            @JsonProperty("delta") Integer delta
    ) {
    }
}
