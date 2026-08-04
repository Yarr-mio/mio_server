package com.mio.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.admin.dto.SessionTimelineResponse;
import com.mio.ai.crisis.CrisisEventRepository;
import com.mio.ai.repository.AiPolicyDecisionRepository;
import com.mio.common.audit.AuditLog;
import com.mio.common.audit.AuditLogRepository;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.crisis.domain.CrisisEvent;
import com.mio.session.domain.Session;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * #287 — audit_logs.resource_id 는 session_id 를 쓰는 action 이 없어(userId 또는
 * crisis_event.id) 세션 타임라인 조회가 구조적으로 항상 빈 배열이었던 버그의 회귀 방지 테스트.
 */
@ExtendWith(MockitoExtension.class)
class AdminSessionServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private AiPolicyDecisionRepository aiPolicyDecisionRepository;
    @Mock private CrisisEventRepository crisisEventRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private MessageEncryptor messageEncryptor;

    private AdminSessionService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminSessionService(
                sessionRepository, messageRepository, aiPolicyDecisionRepository,
                crisisEventRepository, auditLogRepository, messageEncryptor, new ObjectMapper());
    }

    @Test
    @DisplayName("audit_logs는 session_id가 아니라 유저 id + 세션의 crisis_event id로 조회한다")
    void getTimeline_queriesAuditLogsByUserIdAndCrisisEventIds_notSessionId() {
        User user = User.builder().id(userId).build();
        Session session = Session.builder().id(sessionId).user(user).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        UUID crisisEventId = UUID.randomUUID();
        CrisisEvent crisisEvent = CrisisEvent.builder()
                .id(crisisEventId).user(user).session(session)
                .triggerType("keyword").severity(2).operatorReviewed(false)
                .createdAt(OffsetDateTime.now())
                .build();
        when(crisisEventRepository.findBySession_IdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of(crisisEvent));

        when(messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());
        when(aiPolicyDecisionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());
        when(aiPolicyDecisionRepository.sumCostUsdBySessionId(sessionId)).thenReturn(BigDecimal.ZERO);
        when(auditLogRepository.findByResourceIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        SessionTimelineResponse response = service.getTimeline(sessionId);

        assertThat(response.userId()).isEqualTo(userId);
        verify(auditLogRepository).findByResourceIdOrderByCreatedAtAsc(userId.toString());
        verify(auditLogRepository).findByResourceIdOrderByCreatedAtAsc(crisisEventId.toString());
        verify(auditLogRepository, never()).findByResourceIdOrderByCreatedAtAsc(sessionId.toString());
    }

    @Test
    @DisplayName("USER_WITHDRAW 감사 로그가 세션 타임라인에 실제로 포함된다 (회귀 방지)")
    void getTimeline_includesUserWithdrawAuditLog() {
        User user = User.builder().id(userId).build();
        Session session = Session.builder().id(sessionId).user(user).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(crisisEventRepository.findBySession_IdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());
        when(messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());
        when(aiPolicyDecisionRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());
        when(aiPolicyDecisionRepository.sumCostUsdBySessionId(sessionId)).thenReturn(BigDecimal.ZERO);

        AuditLog withdrawLog = AuditLog.builder()
                .userId(userId).action("USER_WITHDRAW").resourceType("user")
                .resourceId(userId.toString()).details("{}")
                .createdAt(OffsetDateTime.now())
                .build();
        when(auditLogRepository.findByResourceIdOrderByCreatedAtAsc(userId.toString()))
                .thenReturn(List.of(withdrawLog));

        SessionTimelineResponse response = service.getTimeline(sessionId);

        assertThat(response.timeline()).hasSize(1);
        assertThat(response.timeline().get(0))
                .containsEntry("type", "audit_log")
                .containsEntry("action", "USER_WITHDRAW");
    }
}
