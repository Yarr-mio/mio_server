package com.mio.ai.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 이슈 #261 — 위기 이력 조회 실패를 "위기 이력 없음"으로 처리하지 않는다.
 *
 * <p>{@code crisisMax = 0} 은 조회 실패와 이력 없음을 같은 값으로 만든다. 그 결과
 * {@code force_judge} 가 빠지고 임계값이 둔해져, 최근 severity 3 위기를 겪은 사용자가
 * DB 장애 중에 가장 둔감한 설정으로 대화하게 된다.
 */
class SafetyProfileBuilderTest {

    private JdbcTemplate jdbcTemplate;
    private MeterRegistry meterRegistry;
    private SafetyProfileBuilder builder;

    private final String userId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        builder = new SafetyProfileBuilder(
                mock(StringRedisTemplate.class), jdbcTemplate, new ObjectMapper(), meterRegistry);
        stubNonCrisisQueries();
    }

    /** 위기 이력 외 나머지 쿼리는 정상 응답 — 프로파일이 default 로 빠지지 않을 만큼의 데이터를 준다. */
    private void stubNonCrisisQueries() {
        when(jdbcTemplate.queryForList(contains("user_beliefs"), any(UUID.class)))
                .thenReturn(List.of(Map.of("belief_kind", "self", "polarity", "negative", "confidence", 0.8)));
        when(jdbcTemplate.queryForList(contains("cbt_patterns"), any(UUID.class)))
                .thenReturn(List.of(Map.of("pattern_type", "catastrophizing", "recurrence_count", 3)));
        when(jdbcTemplate.queryForList(contains("intervention_outcomes"), any(UUID.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForMap(contains("FROM sessions"), any(UUID.class)))
                .thenReturn(Map.of("total_sessions", 3L, "days_since_first", 2.0));
    }

    private void stubCrisisQuery(Integer maxSeverity) {
        when(jdbcTemplate.queryForObject(contains("crisis_events"), eq(Integer.class), any(UUID.class)))
                .thenReturn(maxSeverity);
    }

    private void stubCrisisQueryFailure() {
        when(jdbcTemplate.queryForObject(contains("crisis_events"), eq(Integer.class), any(UUID.class)))
                .thenThrow(new RuntimeException("connection reset"));
    }

    @Test
    @DisplayName("위기 이력 조회에 실패하면 force_judge를 붙이고 임계값을 민감하게 둔다")
    void unresolvedCrisisHistoryFallsBackConservatively() {
        stubCrisisQueryFailure();

        SafetyProfile profile = builder.buildSync(userId);

        assertThat(profile.hasForceJudge())
                .as("조회 실패는 '위기 이력 없음'이 아니다 — 애매한 발화에서 Judge 를 생략하면 안 된다")
                .isTrue();
        assertThat(profile.emotionDropThreshold())
                .as("임계값이 둔해지면 감정 급락 탐지가 늦어진다")
                .isEqualTo(25.0);
        assertThat(profile.repetitiveNegativeCount()).isEqualTo(2);
        assertThat(profile.degraded())
                .as("근거를 확인하지 못한 프로파일임이 값에 남아야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("위기 이력이 실제로 없으면 기본 임계값을 유지한다")
    void resolvedEmptyCrisisHistoryStaysDefault() {
        stubCrisisQuery(0);

        SafetyProfile profile = builder.buildSync(userId);

        assertThat(profile.hasForceJudge()).isFalse();
        assertThat(profile.emotionDropThreshold()).isEqualTo(30.0);
        assertThat(profile.degraded()).isFalse();
    }

    /**
     * 조회 실패와 실제 이력의 결과가 같아 보이면 안 된다.
     *
     * <p>{@code force_judge} 와 임계값은 같아도 {@code recentCrisisSeverityMax} 는 달라야 한다 —
     * 실패 시 severity 를 지어내면 riskPrior 와 crisis_events 분석이 오염된다.
     */
    @Test
    @DisplayName("조회 실패는 실제 위기 이력과 구별된다 — severity를 지어내지 않는다")
    void unresolvedIsDistinguishableFromRealHistory() {
        stubCrisisQuery(3);
        SafetyProfile withHistory = builder.buildSync(userId);

        stubCrisisQueryFailure();
        SafetyProfile unresolved = builder.buildSync(userId);

        assertThat(withHistory.hasForceJudge()).isTrue();
        assertThat(unresolved.hasForceJudge()).isTrue();

        assertThat(withHistory.recentCrisisSeverityMax()).isEqualTo(3);
        assertThat(unresolved.recentCrisisSeverityMax())
                .as("확인하지 못한 severity 를 값으로 채우지 않는다")
                .isZero();

        assertThat(withHistory.degraded()).isFalse();
        assertThat(unresolved.degraded()).isTrue();
    }

    @Test
    @DisplayName("병렬 쿼리가 전부 실패해도 default가 아니라 보수적 프로파일로 떨어진다")
    void totalQueryFailureFallsBackToDegraded() {
        JdbcTemplate broken = mock(JdbcTemplate.class);
        when(broken.queryForList(any(String.class), any(UUID.class)))
                .thenThrow(new RuntimeException("db down"));
        when(broken.queryForObject(any(String.class), eq(Integer.class), any(UUID.class)))
                .thenThrow(new RuntimeException("db down"));
        when(broken.queryForMap(any(String.class), any(UUID.class)))
                .thenThrow(new RuntimeException("db down"));

        SafetyProfile profile = new SafetyProfileBuilder(
                mock(StringRedisTemplate.class), broken, new ObjectMapper(), meterRegistry).buildSync(userId);

        assertThat(profile.hasForceJudge()).isTrue();
        assertThat(profile.emotionDropThreshold()).isEqualTo(25.0);
        assertThat(profile.degraded()).isTrue();
    }

    /**
     * degraded 프로파일은 전체 트래픽에 InputJudge 호출을 한 번씩 더 붙인다. 위기 이력을
     * 확인하지 못한 이상 누가 고위험 사용자인지 알 수 없어 전원에게 {@code force_judge} 를
     * 붙이기 때문이다. 운영이 그 상태를 즉시 볼 수 있어야 해서 지표로 노출한다.
     */
    @Test
    @DisplayName("빌드 결과를 지표로 노출한다 — degraded 급증은 LLM 부하 급증을 뜻한다")
    void buildOutcomeIsExposedAsMetric() {
        stubCrisisQuery(0);
        builder.buildSync(userId);

        stubCrisisQueryFailure();
        builder.buildSync(userId);
        builder.buildSync(userId);

        assertThat(counter("resolved")).isEqualTo(1.0);
        assertThat(counter("degraded")).isEqualTo(2.0);
    }

    private double counter(String outcome) {
        return meterRegistry.find("mio.safety_profile.builds")
                .tag("outcome", outcome)
                .counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    /**
     * 첫 세션에서 위기를 겪은 사용자는 아직 belief 도 cbt_pattern 도 없다 — 둘 다 세션
     * 컨솔리데이션이 끝나야 생기기 때문이다. 그런데 "근거 없음" 조기 반환이 위기 이력을 보지
     * 않으면 그 사용자가 buildDefault 로 떨어져 force_judge·민감 임계값·riskPrior 를 전부 잃는다.
     *
     * <p>조회 실패가 아니라 정상 조회인데도 보호가 사라지는 경로라 degraded 로도 잡히지 않는다.
     */
    @Test
    @DisplayName("belief·pattern이 없어도 위기 이력이 있으면 보호를 유지한다")
    void crisisHistoryAlonePreventsDefaultFallback() {
        when(jdbcTemplate.queryForList(contains("user_beliefs"), any(UUID.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("cbt_patterns"), any(UUID.class)))
                .thenReturn(List.of());
        stubCrisisQuery(3);

        SafetyProfile profile = builder.buildSync(userId);

        assertThat(profile.hasForceJudge())
                .as("최근 severity 3 위기를 겪은 사용자가 default 로 떨어지면 안 된다")
                .isTrue();
        assertThat(profile.recentCrisisSeverityMax()).isEqualTo(3);
        assertThat(profile.riskPriorScore()).isCloseTo(0.9, within(1e-9));
        assertThat(profile.emotionDropThreshold()).isEqualTo(25.0);
        assertThat(profile.degraded())
                .as("조회는 전부 성공했으므로 degraded 가 아니다")
                .isFalse();
    }

    @Test
    @DisplayName("근거가 실제로 하나도 없으면 default로 떨어진다")
    void noEvidenceAtAllFallsBackToDefault() {
        when(jdbcTemplate.queryForList(contains("user_beliefs"), any(UUID.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("cbt_patterns"), any(UUID.class)))
                .thenReturn(List.of());
        stubCrisisQuery(0);

        SafetyProfile profile = builder.buildSync(userId);

        assertThat(profile.hasForceJudge()).isFalse();
        assertThat(profile.emotionDropThreshold()).isEqualTo(30.0);
        assertThat(profile.degraded()).isFalse();
    }

    /**
     * belief 조회 실패도 "신념 없음"과 같은 값(빈 목록)이 되어 조기 반환 조건을 만족시킨다.
     * 위기 이력 조회와 같은 결함 계열이다.
     */
    @Test
    @DisplayName("belief 조회 실패로 비어 보이는 경우도 degraded로 처리한다")
    void unresolvedBeliefQueryIsDegraded() {
        when(jdbcTemplate.queryForList(contains("user_beliefs"), any(UUID.class)))
                .thenThrow(new RuntimeException("connection reset"));
        when(jdbcTemplate.queryForList(contains("cbt_patterns"), any(UUID.class)))
                .thenReturn(List.of());
        stubCrisisQuery(0);

        SafetyProfile profile = builder.buildSync(userId);

        assertThat(profile.degraded())
                .as("조회 실패로 비어 보이는 것과 실제로 비어 있는 것은 다르다")
                .isTrue();
        assertThat(profile.hasForceJudge()).isTrue();
    }

    @Test
    @DisplayName("cbt_pattern 조회 실패도 degraded로 처리한다")
    void unresolvedPatternQueryIsDegraded() {
        when(jdbcTemplate.queryForList(contains("cbt_patterns"), any(UUID.class)))
                .thenThrow(new RuntimeException("connection reset"));
        stubCrisisQuery(0);

        SafetyProfile profile = builder.buildSync(userId);

        assertThat(profile.degraded()).isTrue();
        assertThat(profile.hasForceJudge()).isTrue();
    }

    @Test
    @DisplayName("신규 사용자의 default 프로파일은 degraded가 아니다")
    void defaultProfileIsNotDegraded() {
        SafetyProfile profile = builder.buildDefault(userId);

        assertThat(profile.degraded())
                .as("이력이 없는 것과 확인하지 못한 것은 다르다")
                .isFalse();
        assertThat(profile.hasForceJudge()).isFalse();
    }
}
