package com.mio.ai.cost;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 옵션 A(시간 비례) 배분 계산을 실제 DB로 검증한다 (이슈 #437).
 *
 * <p>{@code EXTRACT(EPOCH FROM (ended_at - started_at))} 같은 DB 함수 의존 로직은
 * mock으로는 검증이 안 돼 실제 Postgres에 대해 돌린다.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class InfraCostAllocatorIntegrationTest {

    @Autowired
    private InfraCostAllocator allocator;

    @Autowired
    private InfraCostSnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedUserIds = new ArrayList<>();
    private final List<UUID> insertedSessionIds = new ArrayList<>();
    private final List<UUID> insertedSnapshotIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        insertedSessionIds.forEach(id -> jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", id));
        insertedUserIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
        insertedSnapshotIds.forEach(id -> snapshotRepository.deleteById(id));
    }

    @Test
    @DisplayName("캐시가 없으면 배분하지 않는다")
    void allocateForSession_noSnapshot_returnsNull() {
        assertThat(allocator.allocateForSession(YearMonth.of(2026, 8), 300L)).isNull();
    }

    @Test
    @DisplayName("월 총청구액을 그 달 총세션-초 대비 세션 duration 비율로 배분한다")
    void allocateForSession_withSnapshotAndSessions_allocatesProportionally() {
        YearMonth august = YearMonth.of(2026, 8);
        saveSnapshot(august, new BigDecimal("18.5"));

        UUID user1 = insertUser();
        UUID user2 = insertUser();
        // 이번 세션 300초 + 다른 세션 700초 = 그 달 총 1000초
        insertEndedSession(user1, "2026-08-10T10:00:00+09:00", "2026-08-10T10:05:00+09:00"); // 300s
        insertEndedSession(user2, "2026-08-11T10:00:00+09:00", "2026-08-11T10:11:40+09:00"); // 700s

        // 18.5 * 300 / 1000 = 5.55
        BigDecimal allocated = allocator.allocateForSession(august, 300L);

        assertThat(allocated).isEqualByComparingTo(new BigDecimal("5.55"));
    }

    @Test
    @DisplayName("유저의 그 달 세션 전체(합산 시간) 기준으로 배분한다")
    void allocateForUserMonth_sumsUserSessionsInMonth() {
        YearMonth august = YearMonth.of(2026, 8);
        saveSnapshot(august, new BigDecimal("18.5"));

        UUID targetUser = insertUser();
        UUID otherUser = insertUser();
        // targetUser: 300s + 200s = 500s, otherUser: 500s → 그 달 총 1000초
        insertEndedSession(targetUser, "2026-08-10T10:00:00+09:00", "2026-08-10T10:05:00+09:00"); // 300s
        insertEndedSession(targetUser, "2026-08-12T10:00:00+09:00", "2026-08-12T10:03:20+09:00"); // 200s
        insertEndedSession(otherUser, "2026-08-11T10:00:00+09:00", "2026-08-11T10:08:20+09:00"); // 500s

        // 18.5 * 500 / 1000 = 9.25
        BigDecimal allocated = allocator.allocateForUserMonth(targetUser, august);

        assertThat(allocated).isEqualByComparingTo(new BigDecimal("9.25"));
    }

    @Test
    @DisplayName("진행 중(미종료)인 세션은 총세션-초 계산에서 제외된다")
    void allocateForSession_excludesUnendedSessions() {
        YearMonth august = YearMonth.of(2026, 8);
        saveSnapshot(august, new BigDecimal("18.5"));

        UUID user1 = insertUser();
        insertEndedSession(user1, "2026-08-10T10:00:00+09:00", "2026-08-10T10:05:00+09:00"); // 300s
        insertActiveSession(user1, "2026-08-11T10:00:00+09:00"); // 미종료 — 집계 제외돼야 함

        // 미종료 세션이 섞여도 총세션-초는 300초만 잡혀야 한다: 18.5 * 300 / 300 = 18.5
        BigDecimal allocated = allocator.allocateForSession(august, 300L);

        assertThat(allocated).isEqualByComparingTo(new BigDecimal("18.5"));
    }

    private void saveSnapshot(YearMonth month, BigDecimal totalCostUsd) {
        InfraCostSnapshot snapshot = snapshotRepository.save(InfraCostSnapshot.builder()
                .billingPeriodStart(month.atDay(1))
                .billingPeriodEnd(month.plusMonths(1).atDay(1))
                .totalCostUsd(totalCostUsd)
                .estimated(true)
                .snapshotAt(OffsetDateTime.now())
                .allocationMethodVersion(InfraCostSyncJob.ALLOCATION_METHOD_VERSION)
                .build());
        insertedSnapshotIds.add(snapshot.getId());
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "infra-cost-it-" + userId);
        insertedUserIds.add(userId);
        return userId;
    }

    private void insertEndedSession(UUID userId, String startedAt, String endedAt) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, started_at, ended_at) " +
                        "VALUES (?, ?, 'mio', 'ended', ?::timestamptz, ?::timestamptz)",
                sessionId, userId, startedAt, endedAt);
        insertedSessionIds.add(sessionId);
    }

    private void insertActiveSession(UUID userId, String startedAt) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, started_at) " +
                        "VALUES (?, ?, 'mio', 'active', ?::timestamptz)",
                sessionId, userId, startedAt);
        insertedSessionIds.add(sessionId);
    }
}
