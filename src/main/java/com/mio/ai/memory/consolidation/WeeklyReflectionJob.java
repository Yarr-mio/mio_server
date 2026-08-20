package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.UserSelfModel;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.ModelRole;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.repository.UserSelfModelRepository;
import com.mio.report.domain.ReportWeek;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Tier 3 Worker — 매주 월요일 04:00 KST 실행 (이슈 #419).
 *
 * 처리:
 * 1. 지난 주 intervention_outcomes 집계 → effective_interventions 갱신
 * 2. recurring trigger_tags 집계
 * 3. LLM 으로 narrative / coaching_direction 생성
 * 4. user_self_model 갱신 (사용자별 독립 트랜잭션)
 * 5. weekly_reports.narrative / coaching_direction UPDATE
 *
 * <p><b>실행 시각이 {@link com.mio.report.job.ReportAggregationJob}(월 03:00) 뒤여야 한다.</b>
 * 5번이 UPDATE 이므로 대상 행이 먼저 존재해야 하는데, 그 행을 만드는 것이 집계 job 이다.
 * 이전에는 일요일 00:00 에 돌아 <b>행이 생기기 28시간 전</b>에 UPDATE 를 시도했고,
 * 게다가 {@code week_start} 를 {@code 오늘 - 7일}(= 일요일)로 계산해 월요일만 저장되는
 * 컬럼과 영영 매칭되지 않았다. 두 원인이 겹쳐 UPDATE 가 항상 0 rows 였다
 * (프로덕션 확인: {@code weekly_reports} 9행 중 narrative 0행).
 *
 * <p>주차 계산은 {@link ReportWeek} 로 통일한다 — 집계 job · 알림 게이트 · 리포트 조회가
 * 모두 같은 헬퍼를 쓰므로, 여기만 다르면 같은 종류의 불일치가 다시 생긴다.
 *
 * <p>부수 효과로 일요일 00:00 의 {@code DailyReflectionJob} 동시 발화도 해소된다 —
 * 두 잡 모두 LLM 을 구동하므로 같은 시각에 겹치면 2 vCPU 를 함께 물었다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyReflectionJob {

    // 주간 회고 출력 상한. 프롬프트가 500자를 요구한다 (~355 토큰).
    private static final int REFLECTION_MAX_COMPLETION_TOKENS = 800;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String NARRATIVE_SYSTEM = """
            당신은 CBT 기반 감정 코칭 AI입니다.
            지난 7일간의 사용자 데이터를 바탕으로 주간 코칭 내러티브를 작성하세요.

            작성 기준:
            - 300~500자 이내
            - 사용자 친화적이고 따뜻한 톤
            - 이번 주 주요 감정 흐름과 진전 사항을 자연스럽게 서술
            - 원문 인용, 진단, 처방 금지
            """;
    private static final String DIRECTION_SYSTEM = """
            다음 주 코칭 방향을 100~150자로 한 문단 작성하세요.
            구체적 행동 1~2가지를 부드럽게 제안하는 형식으로 작성합니다.
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LlmClient llmClient;
    private final ModelCatalog modelCatalog;
    private final UserSelfModelRepository selfModelRepository;
    private final ObjectMapper objectMapper;
    private final MemoryConsentChecker memoryConsentChecker;
    /** 실행 시각을 주입받아 비-월요일 실행도 테스트할 수 있게 한다 (이슈 #415 선례). */
    private final Clock clock;

    @Scheduled(cron = "0 0 4 * * MON", zone = "Asia/Seoul")
    public void run() {
        // 집계 job(월 03:00)이 weekly_reports 행을 만든 뒤에 돌아야 5번 UPDATE 가 성립한다.
        // 주차 계산도 그쪽과 같은 헬퍼를 써야 week_start 가 일치한다 (이슈 #419).
        //
        // weekEnd 가 필요한 이유: 집계 쿼리들이 하한만 걸고 있었는데, 이전 스케줄(일 00:00)은
        // 실행 시각이 주 경계와 같아 "지금까지" 가 곧 상한 역할을 했다. 월 04:00 으로 옮기면
        // 그 우연한 안전장치가 사라져 이번 주 00:00~04:00 데이터가 지난주 회고에 섞인다.
        LocalDate weekStart = ReportWeek.lastWeekStartFrom(LocalDate.now(clock));
        LocalDate weekEnd = ReportWeek.weekEndOf(weekStart);

        log.info("[WeeklyReflectionJob] start weekStart={} weekEnd={}", weekStart, weekEnd);

        List<UUID> userIds = loadActiveUserIds(weekStart, weekEnd);
        log.info("[WeeklyReflectionJob] processing {} users", userIds.size());

        for (UUID userId : userIds) {
            try {
                processUser(userId, weekStart, weekEnd);
            } catch (Exception e) {
                log.warn("[WeeklyReflectionJob] failed userId={}: {}", userId, e.getMessage());
            }
        }
        log.info("[WeeklyReflectionJob] done");
    }

    private void processUser(UUID userId, LocalDate weekStart, LocalDate weekEnd) {
        // 메모리 동의 철회 게이트 (이슈 #453). 대상 선정은 sessions 만 보므로 철회한
        // 사용자도 들어온다 — 여기서 막지 않으면 그 사용자의 집계가 외부 LLM 으로 나가고
        // user_self_model·weekly_reports 에 새 파생 장기 상태가 쌓인다. 집계 전에 끊는다.
        if (!memoryConsentChecker.isRetentionAllowed(userId)) {
            log.info("[WeeklyReflectionJob] memory consent withdrawn, skipping userId={}", userId);
            return;
        }

        // 집계 (읽기 전용, 트랜잭션 불필요)
        Map<String, Double> effectiveMap = aggregateEffectiveInterventions(userId, weekStart, weekEnd);
        List<String> recurringTriggers = aggregateRecurringTriggers(userId, weekStart, weekEnd);
        List<String> dominantEmotions = aggregateDominantEmotions(userId, weekStart, weekEnd);

        // LLM 호출 (트랜잭션 외부 — 커넥션 점유 방지)
        String context = buildContext(effectiveMap, recurringTriggers, dominantEmotions);
        String narrative = generateText(NARRATIVE_SYSTEM, context, userId);
        String coachingDirection = generateText(DIRECTION_SYSTEM, context, userId);

        // 사용자별 독립 트랜잭션으로 저장 (실패해도 다음 사용자에 영향 없음)
        updateSelfModel(userId, dominantEmotions, recurringTriggers, effectiveMap);
        if (narrative != null || coachingDirection != null) {
            updateReport(userId, weekStart, narrative, coachingDirection);
        }
    }

    private List<UUID> loadActiveUserIds(LocalDate weekStart, LocalDate weekEnd) {
        try {
            return jdbcTemplate.query("""
                    SELECT DISTINCT user_id FROM sessions
                    WHERE (started_at AT TIME ZONE 'Asia/Seoul')::date >= ?
                      AND (started_at AT TIME ZONE 'Asia/Seoul')::date <= ?
                      AND status = 'ended'
                    """,
                    (rs, i) -> (UUID) rs.getObject(1),
                    weekStart, weekEnd);
        } catch (Exception e) {
            log.warn("[WeeklyReflectionJob] loadActiveUserIds failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Double> aggregateEffectiveInterventions(UUID userId, LocalDate weekStart, LocalDate weekEnd) {
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            jdbcTemplate.query("""
                    SELECT intervention_kind, AVG(delta) AS avg_delta
                    FROM intervention_outcomes
                    WHERE user_id = ?
                      AND (created_at AT TIME ZONE 'Asia/Seoul')::date >= ?
                      AND (created_at AT TIME ZONE 'Asia/Seoul')::date <= ?
                      AND delta IS NOT NULL
                    GROUP BY intervention_kind
                    ORDER BY avg_delta DESC
                    LIMIT 5
                    """,
                    (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
                        while (rs.next()) {
                            result.put(rs.getString("intervention_kind"), rs.getDouble("avg_delta"));
                        }
                        return null;
                    },
                    userId, weekStart, weekEnd);
        } catch (Exception e) {
            log.debug("[WeeklyReflectionJob] intervention aggregation failed: {}", e.getMessage());
        }
        return result;
    }

    // package-private: memory_status 필터 회귀를 실 DB 로 검증하려면 집계만 따로 불러야 한다.
    List<String> aggregateRecurringTriggers(UUID userId, LocalDate weekStart, LocalDate weekEnd) {
        try {
            // memory_status 필터는 검색기 4곳과 같은 이유로 여기에도 필요하다 (이슈 #453 리뷰).
            // 주간 회고는 로그가 아니라 weekly_reports.narrative 로 사용자에게 그대로 노출되는
            // 산출물이라, 사용자가 정정·비활성화한 요약의 트리거 태그가 여기로 새면
            // "서버가 나를 어떻게 기억하는지 통제한다"는 약속이 깨진다. processUser 의 동의
            // 게이트는 전체 철회만 막으므로 개별 기억 단위는 이 조건이 담당한다.
            return jdbcTemplate.query("""
                    SELECT t AS trigger, COUNT(*) AS cnt
                    FROM session_summaries ss,
                         UNNEST(ss.trigger_tags) t
                    WHERE ss.user_id = ?
                      AND (ss.created_at AT TIME ZONE 'Asia/Seoul')::date >= ?
                      AND (ss.created_at AT TIME ZONE 'Asia/Seoul')::date <= ?
                      AND ss.memory_status = 'active'
                    GROUP BY t ORDER BY cnt DESC LIMIT 5
                    """,
                    (rs, i) -> rs.getString("trigger"),
                    userId, weekStart, weekEnd);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> aggregateDominantEmotions(UUID userId, LocalDate weekStart, LocalDate weekEnd) {
        try {
            return jdbcTemplate.query("""
                    SELECT primary_emotion, COUNT(*) AS cnt
                    FROM emotional_states
                    WHERE user_id = ?
                      AND (created_at AT TIME ZONE 'Asia/Seoul')::date >= ?
                      AND (created_at AT TIME ZONE 'Asia/Seoul')::date <= ?
                    GROUP BY primary_emotion ORDER BY cnt DESC LIMIT 3
                    """,
                    (rs, i) -> rs.getString("primary_emotion"),
                    userId, weekStart, weekEnd);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildContext(Map<String, Double> effective, List<String> triggers, List<String> emotions) {
        return String.format(
                "주요 감정: %s\n반복 트리거: %s\n효과적 개입: %s",
                String.join(", ", emotions),
                String.join(", ", triggers),
                effective.entrySet().stream()
                        .map(e -> e.getKey() + "(+%.1f)".formatted(e.getValue()))
                        .reduce((a, b) -> a + ", " + b).orElse("없음"));
    }

    private String generateText(String systemPrompt, String context, UUID userId) {
        try {
            return llmClient.completeText(LlmRequest.of(modelCatalog.modelFor(ModelRole.WEEKLY_REFLECTION),
                    systemPrompt, context)
                    .withMaxCompletionTokens(REFLECTION_MAX_COMPLETION_TOKENS)
                    .withAttribution("WEEKLY_REFLECTION", userId, null));
        } catch (Exception e) {
            log.warn("[WeeklyReflectionJob] LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSelfModel(UUID userId, List<String> emotions, List<String> triggers,
                                Map<String, Double> effective) {
        try {
            String effectiveJson = objectMapper.writeValueAsString(effective);
            UserSelfModel model = selfModelRepository.findById(userId)
                    .orElse(UserSelfModel.builder().userId(userId).build());
            model.updateFromReflection(emotions, triggers, null, effectiveJson);
            selfModelRepository.save(model);
        } catch (Exception e) {
            log.warn("[WeeklyReflectionJob] self-model update failed userId={}: {}", userId, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateReport(UUID userId, LocalDate weekStart, String narrative, String coachingDirection) {
        try {
            int rows = jdbcTemplate.update("""
                    UPDATE weekly_reports
                    SET narrative = COALESCE(?, narrative),
                        coaching_direction = COALESCE(?, coaching_direction)
                    WHERE user_id = ? AND week_start = ?
                    """,
                    narrative, coachingDirection, userId, weekStart);
            if (rows == 0) {
                // 이 상태가 이슈 #419 의 증상이었다 — debug 로 남기면 "LLM 을 부르고 버리는"
                // 상태가 조용히 유지된다. 집계 job 미실행·week_start 불일치를 즉시 드러낸다.
                log.warn("[WeeklyReflectionJob] report row not found — narrative discarded."
                        + " userId={} weekStart={} (ReportAggregationJob 이 먼저 실행됐는지 확인)",
                        userId, weekStart);
            } else {
                log.debug("[WeeklyReflectionJob] updated report rows={} userId={}", rows, userId);
            }
        } catch (Exception e) {
            log.warn("[WeeklyReflectionJob] report update failed userId={}: {}", userId, e.getMessage());
        }
    }
}
