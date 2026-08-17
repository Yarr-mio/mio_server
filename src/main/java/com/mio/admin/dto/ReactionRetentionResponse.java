package com.mio.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 반응 신호별 7일 재방문율 조회 응답 (이슈 #476, 개선안 문서 §4.4).
 *
 * <p>{@code retentionRate}는 상관관계이지, 비용이나 AI 응답이 리텐션을 만들었다는 인과 근거가
 * 아니다. 표본이 작은 그룹의 비율은 참고용일 뿐이다.
 */
public record ReactionRetentionResponse(
        @JsonProperty("by_reaction") List<ReactionGroup> byReaction
) {
    public record ReactionGroup(
            @JsonProperty("reaction") String reaction,
            @JsonProperty("session_count") long sessionCount,
            @JsonProperty("returned_within_7d_count") long returnedWithin7dCount,
            /** sessionCount가 0이면 null — 0%로 오독되지 않게 한다. */
            @JsonProperty("retention_rate") Double retentionRate
    ) {
    }
}
