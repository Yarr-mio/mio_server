package com.mio.admin.service;

import com.mio.admin.dto.ReactionRetentionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 반응 신호별 7일 재방문율 조회 (이슈 #476, 개선안 문서 §4.4).
 *
 * <p>{@code intervention_outcomes} 각 행(반응 인스턴스 하나)을 기준으로, 그 세션이 끝난 뒤
 * 7일 안에 같은 유저의 다른 세션이 시작됐는지를 {@code user_reaction}별로 집계한다. 신규
 * 계측 없이 {@code intervention_outcomes}·{@code sessions} 두 테이블만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class AdminReactionRetentionService {

    private static final int RETENTION_WINDOW_DAYS = 7;

    private final JdbcTemplate jdbcTemplate;

    public ReactionRetentionResponse getReactionRetention() {
        List<ReactionRetentionResponse.ReactionGroup> groups = jdbcTemplate.query("""
                SELECT io.user_reaction AS reaction,
                       COUNT(*) AS session_count,
                       COUNT(next_session.started_at) AS returned_count
                FROM intervention_outcomes io
                JOIN sessions s ON s.id = io.session_id
                LEFT JOIN LATERAL (
                    SELECT s2.started_at
                    FROM sessions s2
                    WHERE s2.user_id = s.user_id
                      AND s2.started_at > s.ended_at
                      AND s2.started_at <= s.ended_at + (? || ' days')::interval
                    ORDER BY s2.started_at ASC
                    LIMIT 1
                ) next_session ON true
                WHERE s.status = 'ended' AND s.ended_at IS NOT NULL AND io.user_reaction IS NOT NULL
                GROUP BY io.user_reaction
                ORDER BY io.user_reaction
                """,
                (rs, rowNum) -> {
                    long sessionCount = rs.getLong("session_count");
                    long returnedCount = rs.getLong("returned_count");
                    Double retentionRate = sessionCount > 0 ? (double) returnedCount / sessionCount : null;
                    return new ReactionRetentionResponse.ReactionGroup(
                            rs.getString("reaction"), sessionCount, returnedCount, retentionRate);
                },
                RETENTION_WINDOW_DAYS);

        return new ReactionRetentionResponse(groups);
    }
}
