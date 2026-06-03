package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.UserSelfModel;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.repository.UserSelfModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Tier 3 Worker — 매주 일요일 자정 실행.
 *
 * 처리:
 * 1. 지난 7일 intervention_outcomes 집계 → effective_interventions 갱신
 * 2. recurring trigger_tags 집계
 * 3. GPT-4o-mini로 narrative / coaching_direction 생성
 * 4. user_self_model 갱신 (사용자별 독립 트랜잭션)
 * 5. weekly_reports.narrative / coaching_direction UPDATE
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyReflectionJob {

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
    private final UserSelfModelRepository selfModelRepository;
    private final ObjectMapper objectMapper;

    @Scheduled(cron = "0 0 0 * * SUN", zone = "Asia/Seoul")
    public void run() {
        log.info("[WeeklyReflectionJob] start");
        LocalDate weekStart = LocalDate.now(KST).minusDays(7);
        LocalDate weekEnd = LocalDate.now(KST).minusDays(1);

        List<UUID> userIds = loadActiveUserIds(weekStart);
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
        // 집계 (읽기 전용, 트랜잭션 불필요)
        Map<String, Double> effectiveMap = aggregateEffectiveInterventions(userId, weekStart);
        List<String> recurringTriggers = aggregateRecurringTriggers(userId, weekStart);
        List<String> dominantEmotions = aggregateDominantEmotions(userId, weekStart);

        // LLM 호출 (트랜잭션 외부 — 커넥션 점유 방지)
        String context = buildContext(effectiveMap, recurringTriggers, dominantEmotions);
        String narrative = generateText(NARRATIVE_SYSTEM, context);
        String coachingDirection = generateText(DIRECTION_SYSTEM, context);

        // 사용자별 독립 트랜잭션으로 저장 (실패해도 다음 사용자에 영향 없음)
        updateSelfModel(userId, dominantEmotions, recurringTriggers, effectiveMap);
        if (narrative != null || coachingDirection != null) {
            updateReport(userId, weekStart, narrative, coachingDirection);
        }
    }

    private List<UUID> loadActiveUserIds(LocalDate weekStart) {
        try {
            return jdbcTemplate.query("""
                    SELECT DISTINCT user_id FROM sessions
                    WHERE (started_at AT TIME ZONE 'Asia/Seoul')::date >= ? AND status = 'ended'
                    """,
                    (rs, i) -> (UUID) rs.getObject(1),
                    weekStart);
        } catch (Exception e) {
            log.warn("[WeeklyReflectionJob] loadActiveUserIds failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Double> aggregateEffectiveInterventions(UUID userId, LocalDate weekStart) {
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            jdbcTemplate.query("""
                    SELECT intervention_kind, AVG(delta) AS avg_delta
                    FROM intervention_outcomes
                    WHERE user_id = ?
                      AND (created_at AT TIME ZONE 'Asia/Seoul')::date >= ?
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
                    userId, weekStart);
        } catch (Exception e) {
            log.debug("[WeeklyReflectionJob] intervention aggregation failed: {}", e.getMessage());
        }
        return result;
    }

    private List<String> aggregateRecurringTriggers(UUID userId, LocalDate weekStart) {
        try {
            return jdbcTemplate.query("""
                    SELECT t AS trigger, COUNT(*) AS cnt
                    FROM session_summaries ss,
                         UNNEST(ss.trigger_tags) t
                    WHERE ss.user_id = ?
                      AND (ss.created_at AT TIME ZONE 'Asia/Seoul')::date >= ?
                    GROUP BY t ORDER BY cnt DESC LIMIT 5
                    """,
                    (rs, i) -> rs.getString("trigger"),
                    userId, weekStart);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> aggregateDominantEmotions(UUID userId, LocalDate weekStart) {
        try {
            return jdbcTemplate.query("""
                    SELECT primary_emotion, COUNT(*) AS cnt
                    FROM emotional_states
                    WHERE user_id = ?
                      AND (created_at AT TIME ZONE 'Asia/Seoul')::date >= ?
                    GROUP BY primary_emotion ORDER BY cnt DESC LIMIT 3
                    """,
                    (rs, i) -> rs.getString("primary_emotion"),
                    userId, weekStart);
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

    private String generateText(String systemPrompt, String context) {
        try {
            return llmClient.complete(LlmRequest.of("gpt-4o-mini", systemPrompt, context));
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
            log.debug("[WeeklyReflectionJob] updated report rows={} userId={}", rows, userId);
        } catch (Exception e) {
            log.warn("[WeeklyReflectionJob] report update failed userId={}: {}", userId, e.getMessage());
        }
    }
}
