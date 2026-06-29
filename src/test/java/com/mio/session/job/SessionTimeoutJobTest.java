package com.mio.session.job;

import com.mio.ai.memory.consolidation.SessionEndedEvent;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.session.domain.Session;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTimeoutJobTest {

    @Test
    @DisplayName("타임아웃 종료 update는 cutoff 조건을 원자적으로 다시 검증한다")
    void run_rechecks_timeout_cutoff_in_atomic_update() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        WorkingMemory workingMemory = mock(WorkingMemory.class);
        when(workingMemory.getSocraticQuestionCount(any())).thenReturn(0);
        SessionTimeoutJob job = new SessionTimeoutJob(sessionRepository, eventPublisher, transactionTemplate, workingMemory);

        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Session session = buildSession(userId, sessionId);

        when(sessionRepository.findTimedOutActiveSessions(any())).thenReturn(List.of(session));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(sessionRepository.endSessionIfActive(eq(sessionId), any(), any())).thenReturn(1);

        job.run();

        ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(sessionRepository).findTimedOutActiveSessions(cutoffCaptor.capture());
        verify(sessionRepository).endSessionIfActive(eq(sessionId), eq(cutoffCaptor.getValue()), any());
        verify(eventPublisher).publishEvent(any(SessionEndedEvent.class));
        assertThat(cutoffCaptor.getValue()).isBefore(OffsetDateTime.now());
    }

    @Test
    @DisplayName("원자적 update가 0을 반환하면 이미 종료된 세션이므로 이벤트를 발행하지 않는다")
    void run_does_not_publish_event_when_atomic_update_skipped() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        WorkingMemory workingMemory = mock(WorkingMemory.class);
        when(workingMemory.getSocraticQuestionCount(any())).thenReturn(0);
        SessionTimeoutJob job = new SessionTimeoutJob(sessionRepository, eventPublisher, transactionTemplate, workingMemory);

        UUID sessionId = UUID.randomUUID();
        Session session = buildSession(UUID.randomUUID(), sessionId);

        when(sessionRepository.findTimedOutActiveSessions(any())).thenReturn(List.of(session));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        // 다른 인스턴스가 먼저 처리한 상황 시뮬레이션
        when(sessionRepository.endSessionIfActive(eq(sessionId), any(), any())).thenReturn(0);

        job.run();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("한 세션 처리 실패가 나머지 세션 처리를 막지 않는다")
    void run_continues_remaining_sessions_when_one_fails() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        WorkingMemory workingMemory = mock(WorkingMemory.class);
        when(workingMemory.getSocraticQuestionCount(any())).thenReturn(0);
        SessionTimeoutJob job = new SessionTimeoutJob(sessionRepository, eventPublisher, transactionTemplate, workingMemory);

        UUID sessionId1 = UUID.randomUUID();
        UUID sessionId2 = UUID.randomUUID();
        Session session1 = buildSession(UUID.randomUUID(), sessionId1);
        Session session2 = buildSession(UUID.randomUUID(), sessionId2);

        when(sessionRepository.findTimedOutActiveSessions(any())).thenReturn(List.of(session1, session2));
        when(transactionTemplate.execute(any()))
                .thenThrow(new RuntimeException("DB error on session1"))  // session1 실패
                .thenAnswer(invocation -> {                               // session2 성공
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
        when(sessionRepository.endSessionIfActive(eq(sessionId2), any(), any())).thenReturn(1);

        job.run();

        verify(eventPublisher, times(1)).publishEvent(any(SessionEndedEvent.class));
    }

    private static Session buildSession(UUID userId, UUID sessionId) {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();
        ReflectionTestUtils.setField(user, "id", userId);
        Session session = Session.builder()
                .user(user)
                .characterId("mio")
                .build();
        ReflectionTestUtils.setField(session, "id", sessionId);
        return session;
    }
}
