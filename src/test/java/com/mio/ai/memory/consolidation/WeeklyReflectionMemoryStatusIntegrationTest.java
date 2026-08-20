package com.mio.ai.memory.consolidation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 회고의 트리거 집계가 정정·비활성화된 기억을 회수하지 않는지 검증한다 (이슈 #453).
 *
 * <p>주간 회고는 내부 로그가 아니라 {@code weekly_reports.narrative} 로 사용자에게 그대로
 * 노출되는 산출물이다. 검색기 4곳에는 {@code memory_status} 필터가 들어갔지만 이 집계 경로가
 * 빠지면, 사용자가 껐다고 생각한 요약의 트리거 태그가 주간 인사이트로 되살아난다.
 * {@code processUser} 의 동의 게이트는 <b>전체 철회</b>만 막으므로 개별 기억 단위 통제는
 * 이 쿼리의 필터가 유일한 방어선이다.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class WeeklyReflectionMemoryStatusIntegrationTest {

    private static final String ACTIVE_TRIGGER = "work_stress";
    private static final String DISABLED_TRIGGER = "family_conflict";
    private static final String CORRECTED_TRIGGER = "sleep_deprivation";

    @Autowired private WeeklyReflectionJob weeklyReflectionJob;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private LocalDate weekStart;
    private LocalDate weekEnd;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        weekStart = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(6);
        // 집계에 상한이 생겼다 (이슈 #419). 오늘 삽입한 요약이 구간에 들어오도록 상한을 오늘로 둔다.
        weekEnd = LocalDate.now(ZoneId.of("Asia/Seoul"));

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "weekly-memstatus-" + userId);

        insertSummary(ACTIVE_TRIGGER, "active");
        insertSummary(DISABLED_TRIGGER, "disabled");
        insertSummary(CORRECTED_TRIGGER, "corrected");
    }

    @Test
    @DisplayName("주간 트리거 집계는 active 기억만 회수한다 — disabled·corrected 는 0건")
    void aggregateRecurringTriggers_excludesDisabledAndCorrectedMemories() {
        List<String> triggers = weeklyReflectionJob.aggregateRecurringTriggers(userId, weekStart, weekEnd);

        assertThat(triggers).containsExactly(ACTIVE_TRIGGER);
        assertThat(triggers).doesNotContain(DISABLED_TRIGGER, CORRECTED_TRIGGER);
    }

    @Test
    @DisplayName("모든 기억을 비활성화하면 주간 트리거 집계는 비어 있다")
    void aggregateRecurringTriggers_returnsEmptyWhenAllMemoriesDisabled() {
        jdbcTemplate.update(
                "UPDATE session_summaries SET memory_status = 'disabled' WHERE user_id = ?", userId);

        assertThat(weeklyReflectionJob.aggregateRecurringTriggers(userId, weekStart, weekEnd)).isEmpty();
    }

    private void insertSummary(String triggerTag, String memoryStatus) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, ended_at) "
                        + "VALUES (?, ?, 'mio', 'ended', now())",
                sessionId, userId);
        jdbcTemplate.update(
                """
                INSERT INTO session_summaries
                    (id, user_id, session_id, character_id, summary_text, embedding_status,
                     trigger_tags, memory_status, created_at)
                VALUES (?, ?, ?, 'mio', ?, 'done', ARRAY[?]::text[], ?, now() - interval '1 hour')
                """,
                UUID.randomUUID(), userId, sessionId, "요약 " + triggerTag, triggerTag, memoryStatus);
    }
}
