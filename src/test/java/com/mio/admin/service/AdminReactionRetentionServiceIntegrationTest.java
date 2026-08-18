package com.mio.admin.service;

import com.mio.admin.dto.ReactionRetentionResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 반응 신호별 7일 재방문율 집계를 실제 DB로 검증한다 (이슈 #476).
 *
 * <p>LATERAL 조인 같은 DB 함수 의존 로직은 mock으로는 검증이 안 돼 실제 Postgres에 대해 돌린다.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class AdminReactionRetentionServiceIntegrationTest {

    @Autowired
    private AdminReactionRetentionService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedOutcomeIds = new ArrayList<>();
    private final List<UUID> insertedSessionIds = new ArrayList<>();
    private final List<UUID> insertedUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        insertedOutcomeIds.forEach(id -> jdbcTemplate.update("DELETE FROM intervention_outcomes WHERE id = ?", id));
        insertedSessionIds.forEach(id -> jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", id));
        insertedUserIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    @DisplayName("positive 2건 중 1건만 7일 안에 재방문하면 retention_rate가 0.5다")
    void getReactionRetention_mixedReturns_computesRate() {
        UUID user1 = insertUser();
        UUID session1 = insertEndedSession(user1, "2026-08-01T10:00:00+09:00", "2026-08-01T10:30:00+09:00");
        insertSession(user1, "2026-08-03T10:00:00+09:00", "ended"); // 7일 안 재방문
        insertOutcome(user1, session1, "positive");

        UUID user2 = insertUser();
        UUID session2 = insertEndedSession(user2, "2026-08-01T10:00:00+09:00", "2026-08-01T10:30:00+09:00");
        insertSession(user2, "2026-08-20T10:00:00+09:00", "ended"); // 7일 밖 재방문
        insertOutcome(user2, session2, "positive");

        ReactionRetentionResponse response = service.getReactionRetention();

        ReactionRetentionResponse.ReactionGroup positive = findGroup(response, "positive");
        assertThat(positive.sessionCount()).isEqualTo(2);
        assertThat(positive.returnedWithin7dCount()).isEqualTo(1);
        assertThat(positive.retentionRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("재방문이 하나도 없으면 retention_rate가 0.0이다 (null이 아니라 실제 0)")
    void getReactionRetention_noReturns_rateIsZero() {
        UUID user = insertUser();
        UUID session = insertEndedSession(user, "2026-08-01T10:00:00+09:00", "2026-08-01T10:30:00+09:00");
        insertOutcome(user, session, "negative");

        ReactionRetentionResponse response = service.getReactionRetention();

        ReactionRetentionResponse.ReactionGroup negative = findGroup(response, "negative");
        assertThat(negative.sessionCount()).isEqualTo(1);
        assertThat(negative.returnedWithin7dCount()).isEqualTo(0);
        assertThat(negative.retentionRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("세션이 아직 종료 안 됐으면(active) 집계에서 제외된다")
    void getReactionRetention_excludesUnendedSessions() {
        UUID user = insertUser();
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, started_at) " +
                        "VALUES (?, ?, 'mio', 'active', '2026-08-01T10:00:00+09:00'::timestamptz)",
                sessionId, user);
        insertedSessionIds.add(sessionId);
        insertOutcome(user, sessionId, "neutral");

        ReactionRetentionResponse response = service.getReactionRetention();

        assertThat(findGroupOptional(response, "neutral")).isEmpty();
    }

    private ReactionRetentionResponse.ReactionGroup findGroup(ReactionRetentionResponse response, String reaction) {
        return findGroupOptional(response, reaction)
                .orElseThrow(() -> new AssertionError("no group for reaction=" + reaction));
    }

    private Optional<ReactionRetentionResponse.ReactionGroup> findGroupOptional(
            ReactionRetentionResponse response, String reaction) {
        return response.byReaction().stream().filter(g -> g.reaction().equals(reaction)).findFirst();
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "reaction-retention-it-" + userId);
        insertedUserIds.add(userId);
        return userId;
    }

    private UUID insertEndedSession(UUID userId, String startedAt, String endedAt) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, started_at, ended_at) " +
                        "VALUES (?, ?, 'mio', 'ended', ?::timestamptz, ?::timestamptz)",
                sessionId, userId, startedAt, endedAt);
        insertedSessionIds.add(sessionId);
        return sessionId;
    }

    private void insertSession(UUID userId, String startedAt, String status) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, started_at) " +
                        "VALUES (?, ?, 'mio', ?, ?::timestamptz)",
                sessionId, userId, status, startedAt);
        insertedSessionIds.add(sessionId);
    }

    private void insertOutcome(UUID userId, UUID sessionId, String reaction) {
        UUID outcomeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO intervention_outcomes (id, user_id, session_id, intervention_kind, user_reaction) " +
                        "VALUES (?, ?, ?, 'breathing_exercise', ?)",
                outcomeId, userId, sessionId, reaction);
        insertedOutcomeIds.add(outcomeId);
    }
}
