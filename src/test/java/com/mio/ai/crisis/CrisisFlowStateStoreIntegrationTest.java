package com.mio.ai.crisis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class CrisisFlowStateStoreIntegrationTest {

    @Autowired
    private CrisisFlowStateStore store;

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
                userId, "crisis-flow-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("초기 상태와 닫힌 응답 값을 저장하고 전이를 감사 행으로 남긴다")
    void persistsStateAndTransitionWithoutRawMessage() {
        store.begin(sessionId, userId);
        CrisisFlowSnapshot initial = store.find(sessionId).orElseThrow();
        assertThat(initial.stage()).isEqualTo(CrisisFlowStage.CURRENT_INTENT);
        assertThat(initial.status()).isEqualTo(CrisisFlowStatus.ACTIVE);

        store.advance(sessionId, CrisisFlowStage.CURRENT_INTENT, CrisisAnswer.YES,
                CrisisFlowStage.PLAN, CrisisFlowStatus.ACTIVE);

        CrisisFlowSnapshot advanced = store.find(sessionId).orElseThrow();
        assertThat(advanced.stage()).isEqualTo(CrisisFlowStage.PLAN);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_intent FROM crisis_flow_states WHERE session_id = ?",
                String.class, sessionId)).isEqualTo("yes");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM crisis_flow_transitions WHERE session_id = ? AND answer = 'yes'",
                Integer.class, sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("현재 stage와 어긋난 stale 전이는 거부한다")
    void rejectsStaleTransition() {
        store.begin(sessionId, userId);

        assertThatThrownBy(() -> store.advance(
                sessionId, CrisisFlowStage.PLAN, CrisisAnswer.YES,
                CrisisFlowStage.MEANS, CrisisFlowStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("DB도 stage·status·응답값의 스키마 밖 값을 거부한다")
    void databaseRejectsOutOfSchemaValues() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO crisis_flow_states
                    (session_id, user_id, stage, status, current_intent)
                VALUES (?, ?, 'free_chat', 'active', 'maybe')
                """, sessionId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("상태 행 유실 시에도 기존 crisis_event를 복구 근거로 찾는다")
    void findsCrisisEvidenceIndependentlyFromStateRow() {
        jdbcTemplate.update(
                """
                INSERT INTO crisis_events (user_id, session_id, trigger_type, severity)
                VALUES (?, ?, 'keyword', 3)
                """, userId, sessionId);

        assertThat(store.find(sessionId)).isEmpty();
        assertThat(store.hasCrisisEvent(sessionId)).isTrue();
    }
}
