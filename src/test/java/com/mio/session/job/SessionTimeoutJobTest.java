package com.mio.session.job;

import com.mio.ai.memory.consolidation.SessionEndedEvent;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTimeoutJobTest {

    @Test
    @DisplayName("타임아웃 종료 update는 cutoff 조건을 원자적으로 다시 검증한다")
    void run_rechecks_timeout_cutoff_in_atomic_update() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        SessionTimeoutJob job = new SessionTimeoutJob(sessionRepository, eventPublisher, transactionTemplate);

        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
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
}
