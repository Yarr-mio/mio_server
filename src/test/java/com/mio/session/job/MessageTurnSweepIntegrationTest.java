package com.mio.session.job;

import com.mio.support.MioIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고착된 턴 회수의 실 DB 검증 (이슈 #365, 로드맵 §12 P0-7).
 *
 * <p><b>이 테스트에는 의도적으로 {@code @Transactional} 이 없다.</b> 테스트에 앰비언트
 * 트랜잭션이 걸려 있으면 회수 쿼리가 자기 경계를 갖지 못해도 테스트 트랜잭션에 얹혀
 * 통과한다 — 그러면 검증하려던 대상(커밋이 실제로 일어나는가)을 그대로 통과시킨다.
 * {@code #356} 후속 작업에서 확인된 형태다.
 */
@MioIntegrationTest
class MessageTurnSweepIntegrationTest {

    @Autowired
    private MessageTurnSweepJob sweepJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "turn-sweep-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);
    }

    @AfterEach
    void tearDown() {
        // message_turns 는 sessions FK 의 ON DELETE CASCADE 로 함께 제거된다.
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("오래 generating 에 머문 턴을 failed 로 회수하고 커밋한다")
    void reclaimsStuckGeneratingTurn() {
        UUID turnId = insertTurn("generating", minutesAgo(30), null);

        sweepJob.run();

        // 앰비언트 트랜잭션이 없으므로 이 조회는 커밋된 상태만 본다.
        assertThat(statusOf(turnId)).isEqualTo("failed");
        assertThat(finishedReasonOf(turnId)).isEqualTo("abandoned");
    }

    @Test
    @DisplayName("하트비트가 살아 있는 턴은 회수하지 않는다")
    void leavesLiveTurnAlone() {
        // TurnHeartbeat 이 25초마다 updated_at 을 민다. 방금 갱신된 턴은 살아 있는 턴이다.
        UUID turnId = insertTurn("generating", minutesAgo(1), null);

        sweepJob.run();

        assertThat(statusOf(turnId)).isEqualTo("generating");
        assertThat(finishedReasonOf(turnId)).isNull();
    }

    @Test
    @DisplayName("이미 끝난 턴의 종료 사유를 덮어쓰지 않는다")
    void doesNotTouchTerminalTurns() {
        UUID turnId = insertTurn("completed", minutesAgo(30), "stop");

        sweepJob.run();

        assertThat(statusOf(turnId)).isEqualTo("completed");
        assertThat(finishedReasonOf(turnId)).isEqualTo("stop");
    }

    @Test
    @DisplayName("회수 대상이 없으면 아무것도 바꾸지 않는다")
    void isNoOpWhenNothingIsStuck() {
        UUID fresh = insertTurn("generating", minutesAgo(1), null);
        UUID done = insertTurn("completed", minutesAgo(120), "stop");

        sweepJob.run();

        assertThat(statusOf(fresh)).isEqualTo("generating");
        assertThat(statusOf(done)).isEqualTo("completed");
    }

    private UUID insertTurn(String status, OffsetDateTime updatedAt, String finishedReason) {
        UUID turnId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO message_turns
                    (id, session_id, user_id, status, lease_token, finished_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                turnId, sessionId, userId, status, UUID.randomUUID(), finishedReason, updatedAt, updatedAt);
        return turnId;
    }

    private OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
    }

    private String statusOf(UUID turnId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM message_turns WHERE id = ?", String.class, turnId);
    }

    private String finishedReasonOf(UUID turnId) {
        return jdbcTemplate.queryForObject(
                "SELECT finished_reason FROM message_turns WHERE id = ?", String.class, turnId);
    }
}
