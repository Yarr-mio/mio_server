package com.mio.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.admin.dto.SessionTimelineResponse;
import com.mio.ai.crisis.CrisisEventRepository;
import com.mio.ai.domain.AiPolicyDecision;
import com.mio.ai.repository.AiPolicyDecisionRepository;
import com.mio.common.audit.AuditLog;
import com.mio.common.audit.AuditLogRepository;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.crisis.domain.CrisisEvent;
import com.mio.session.domain.Message;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 운영자가 세션 하나의 흐름·오류·Safety 사건을 시간순으로 통합 조회한다 (Sprint01 DoD).
 *
 * <p>4개 소스(messages, ai_policy_decisions, crisis_events, audit_logs)를 각각 조회한 뒤
 * 애플리케이션 레벨에서 시간순 병합한다 — 소스별 컬럼 모양이 달라 SQL UNION으로는 묶기 어렵다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminSessionService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AiPolicyDecisionRepository aiPolicyDecisionRepository;
    private final CrisisEventRepository crisisEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final MessageEncryptor messageEncryptor;
    private final ObjectMapper objectMapper;

    private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE = new TypeReference<>() {};

    @Transactional(readOnly = true)
    public SessionTimelineResponse getTimeline(UUID sessionId) {
        var session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        List<CrisisEvent> crisisEvents = crisisEventRepository.findBySession_IdOrderByCreatedAtAsc(sessionId);

        // audit_logs.resource_id 는 행위 대상에 따라 session_id(탈퇴·동의)이거나
        // crisis_event.id(위기검토)다 — 이 세션에 속한 위기 이벤트들의 검토 기록도 같이 모아야
        // "세션의 흐름·Safety 사건을 처음부터 끝까지 추적"이 실제로 성립한다.
        List<String> auditResourceIds = Stream.concat(
                Stream.of(sessionId.toString()),
                crisisEvents.stream().map(c -> c.getId().toString())
        ).toList();

        List<TimelineItem> items = Stream.of(
                        messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId).stream()
                                .map(this::messageItem),
                        aiPolicyDecisionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                                .map(this::policyDecisionItem),
                        crisisEvents.stream()
                                .map(this::crisisEventItem),
                        auditResourceIds.stream()
                                .flatMap(resourceId -> auditLogRepository.findByResourceIdOrderByCreatedAtAsc(resourceId).stream())
                                .map(this::auditLogItem)
                )
                .flatMap(s -> s)
                .sorted(Comparator.comparing(TimelineItem::at))
                .toList();

        List<Map<String, Object>> timeline = items.stream().map(TimelineItem::data).toList();
        BigDecimal sessionCostUsd = aiPolicyDecisionRepository.sumCostUsdBySessionId(sessionId);

        return new SessionTimelineResponse(sessionId, session.getUser().getId(), sessionCostUsd, timeline);
    }

    private record TimelineItem(OffsetDateTime at, Map<String, Object> data) {
    }

    private TimelineItem messageItem(Message m) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "message");
        data.put("at", m.getCreatedAt().toString());
        data.put("role", m.getRole().name().toLowerCase());
        data.put("content", new String(messageEncryptor.decrypt(m.getContentCiphertext()), StandardCharsets.UTF_8));
        data.put("emotion_score", m.getEmotionScore());
        data.put("bias_type", m.getBiasType());
        data.put("is_crisis_flagged", m.isCrisisFlagged());
        return new TimelineItem(m.getCreatedAt(), data);
    }

    private TimelineItem policyDecisionItem(AiPolicyDecision d) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "policy_decision");
        data.put("at", d.getCreatedAt().toString());
        data.put("risk_level", d.getRiskLevel());
        data.put("action", d.getAction());
        data.put("delivery_mode", d.getDeliveryMode());
        data.put("trace", parseJson(d.getTrace(), "ai_policy_decisions.trace", d.getId()));
        return new TimelineItem(d.getCreatedAt(), data);
    }

    private TimelineItem crisisEventItem(CrisisEvent c) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "crisis_event");
        data.put("at", c.getCreatedAt().toString());
        data.put("trigger_type", c.getTriggerType());
        data.put("severity", c.getSeverity());
        data.put("operator_reviewed", c.isOperatorReviewed());
        data.put("review_action", c.getReviewAction());
        data.put("operator_note", c.getOperatorNote());
        return new TimelineItem(c.getCreatedAt(), data);
    }

    private TimelineItem auditLogItem(AuditLog a) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "audit_log");
        data.put("at", a.getCreatedAt().toString());
        data.put("action", a.getAction());
        data.put("details", parseJson(a.getDetails(), "audit_logs.details", a.getId()));
        return new TimelineItem(a.getCreatedAt(), data);
    }

    private Map<String, Object> parseJson(String json, String fieldDescription, UUID rowId) {
        try {
            return objectMapper.readValue(json, JSON_MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse {} for session timeline: id={}", fieldDescription, rowId, e);
            return null;
        }
    }
}
