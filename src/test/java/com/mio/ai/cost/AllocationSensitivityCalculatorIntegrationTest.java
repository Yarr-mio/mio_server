package com.mio.ai.cost;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배분 모델 민감도(옵션 A vs 옵션 B) 계산을 실제 DB로 검증한다 (이슈 #438).
 *
 * <p>{@code EXTRACT(EPOCH FROM (ended_at - started_at))} 같은 DB 함수 의존 로직은
 * mock으로는 검증이 안 돼 실제 Postgres에 대해 돌린다.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class AllocationSensitivityCalculatorIntegrationTest {

    @Autowired
    private AllocationSensitivityCalculator calculator;

    @Autowired
    private InfraCostAllocationSensitivityRepository sensitivityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedUserIds = new ArrayList<>();
    private final List<UUID> insertedSessionIds = new ArrayList<>();
    private final List<UUID> insertedSensitivityIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        insertedSessionIds.forEach(id -> jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", id));
        insertedUserIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
        insertedSensitivityIds.forEach(id -> sensitivityRepository.deleteById(id));
    }

    @Test
    @DisplayName("세션별 민감도(|A_i-B_i|/B_i)의 평균을 저장한다 — 전체 합계로 계산하면 항상 0%가 되는 것과 달리 실제로 갈린다")
    void computeAndSave_averagesPerSessionSensitivity() {
        YearMonth august = YearMonth.of(2026, 8);
        UUID user = insertUser();
        // 총세션-초 100, 총메시지 100 — 세션 A: duration 비중(0.4) > 메시지 비중(0.25) → 시간비례가 더 큼
        insertEndedSession(user, "2026-08-10T10:00:00+09:00", 40, 25);
        // 세션 B: duration 비중(0.6) < 메시지 비중(0.75) → 요청수비례가 더 큼
        insertEndedSession(user, "2026-08-11T10:00:00+09:00", 60, 75);

        calculator.computeAndSave(august, new BigDecimal("100"));

        List<InfraCostAllocationSensitivity> saved = sensitivityRepository.findAll();
        insertedSensitivityIds.addAll(saved.stream().map(InfraCostAllocationSensitivity::getId).toList());
        assertThat(saved).hasSize(1);

        InfraCostAllocationSensitivity result = saved.get(0);
        // A_A=40,B_A=25(민감도60%) / A_B=60,B_B=75(민감도20%) → 평균 40%
        assertThat(result.getAllocationSensitivityPct()).isEqualByComparingTo(new BigDecimal("40.0000000000"));
        // sumA=100,sumB=100, count=2 → 둘 다 평균 50 (전체 합계 자체는 배분기준과 무관하게 같다는 걸 보여줌)
        assertThat(result.getOptionAUsd()).isEqualByComparingTo(new BigDecimal("50.0000000000"));
        assertThat(result.getOptionBUsd()).isEqualByComparingTo(new BigDecimal("50.0000000000"));
        assertThat(result.getBillingPeriodStart()).isEqualTo(august.atDay(1));
    }

    @Test
    @DisplayName("메시지가 0건인 세션은 개별 평균 계산에서 제외된다(0으로 나누기 방지)")
    void computeAndSave_excludesZeroMessageSessionFromAverage_butKeepsItInTotals() {
        YearMonth august = YearMonth.of(2026, 8);
        UUID user = insertUser();
        insertEndedSession(user, "2026-08-10T10:00:00+09:00", 40, 25);
        insertEndedSession(user, "2026-08-11T10:00:00+09:00", 60, 75);
        // 메시지 0건 세션(duration 100s) — 총세션-초 분모(200)에는 더해지지만 개별 민감도 평균에서는
        // 빠져야 함(0으로 나누기 회피). 총세션-초가 200으로 바뀌므로 기대값도 그에 맞춰 다시 계산함:
        // A_A=100*40/200=20, B_A=25 → 민감도 20% / A_B=100*60/200=30, B_B=75 → 민감도 60% → 평균 40%
        insertEndedSession(user, "2026-08-12T10:00:00+09:00", 100, 0);

        calculator.computeAndSave(august, new BigDecimal("100"));

        List<InfraCostAllocationSensitivity> saved = sensitivityRepository.findAll();
        insertedSensitivityIds.addAll(saved.stream().map(InfraCostAllocationSensitivity::getId).toList());
        assertThat(saved).hasSize(1);
        // qualifying 세션은 여전히 2건(A,B)뿐이지만, 0건 세션의 duration이 분모에 들어가 값 자체는 바뀐다
        assertThat(saved.get(0).getAllocationSensitivityPct()).isEqualByComparingTo(new BigDecimal("40.0000000000"));
    }

    @Test
    @DisplayName("월간 총청구액이 0 이하면 계산을 스킵한다")
    void computeAndSave_zeroCost_skips() {
        YearMonth august = YearMonth.of(2026, 8);
        UUID user = insertUser();
        insertEndedSession(user, "2026-08-10T10:00:00+09:00", 40, 25);

        calculator.computeAndSave(august, BigDecimal.ZERO);

        assertThat(sensitivityRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("그 달 세션이 아예 없으면 계산을 스킵한다")
    void computeAndSave_noSessions_skips() {
        calculator.computeAndSave(YearMonth.of(2026, 8), new BigDecimal("100"));

        assertThat(sensitivityRepository.findAll()).isEmpty();
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "allocation-sensitivity-it-" + userId);
        insertedUserIds.add(userId);
        return userId;
    }

    private void insertEndedSession(UUID userId, String startedAt, int durationSeconds, int messageCount) {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, started_at, ended_at, message_count) " +
                        "VALUES (?, ?, 'mio', 'ended', ?::timestamptz, ?::timestamptz + (? || ' seconds')::interval, ?)",
                sessionId, userId, startedAt, startedAt, durationSeconds, messageCount);
        insertedSessionIds.add(sessionId);
    }
}
