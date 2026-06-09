package com.mio.session.service;

import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.orchestrator.ConversationOrchestrator;
import com.mio.ai.profile.ContextPreWarmer;
import com.mio.common.error.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import com.mio.session.dto.ActiveSessionResponse;
import com.mio.session.dto.CreateSessionRequest;
import com.mio.session.dto.SendMessageRequest;
import com.mio.session.dto.SessionResponse;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SessionSummaryRepository sessionSummaryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SessionMessagePersistenceService sessionMessagePersistenceService;
    @Mock private ConversationOrchestrator conversationOrchestrator;
    @Mock private WorkingMemory workingMemory;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ContextPreWarmer contextPreWarmer;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private SessionService sessionService;
    private UUID userId;
    private User mockUser;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        sessionService = new SessionService(
                sessionRepository, sessionSummaryRepository, userRepository,
                sessionMessagePersistenceService, conversationOrchestrator, workingMemory,
                eventPublisher, contextPreWarmer, redisTemplate
        );
        userId = UUID.randomUUID();
        mockUser = User.builder()
                .socialProvider("kakao")
                .socialId("test-id")
                .privacyConsent(true)
                .build();
        mockUser.completeOnboarding("mio");
        ReflectionTestUtils.setField(mockUser, "id", userId);
    }

    @Test
    @DisplayName("세션 생성 성공 시 SessionResponse를 반환한다")
    void createSession_success_returnsSessionResponse() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(sessionRepository.existsByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(false);
        Session session = Session.builder()
                .user(mockUser)
                .characterId("mio")
                .build();
        when(sessionRepository.save(any())).thenReturn(session);

        SessionResponse response = sessionService.createSession(userId, new CreateSessionRequest("mio"));

        assertThat(response.characterId()).isEqualTo("mio");
        assertThat(response.status()).isEqualTo("active");
    }

    @Test
    @DisplayName("character_id 생략 시 preferred_character_id를 사용한다")
    void createSession_noCharacterId_usesPreferred() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(sessionRepository.existsByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(false);
        Session session = Session.builder()
                .user(mockUser)
                .characterId("mio")
                .build();
        when(sessionRepository.save(any())).thenReturn(session);

        SessionResponse response = sessionService.createSession(userId, new CreateSessionRequest(null));

        assertThat(response.characterId()).isEqualTo("mio");
    }

    @Test
    @DisplayName("온보딩 미완료 사용자는 ONBOARDING_REQUIRED 예외가 발생한다")
    void createSession_onboardingIncomplete_throws() {
        User incompleteUser = User.builder()
                .socialProvider("kakao")
                .socialId("test-id")
                .privacyConsent(true)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(incompleteUser));

        assertThatThrownBy(() -> sessionService.createSession(userId, new CreateSessionRequest("mio")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_REQUIRED));
    }

    @Test
    @DisplayName("이미 활성 세션이 있으면 SESSION_ALREADY_ACTIVE 예외가 발생한다")
    void createSession_activeSessionExists_throws() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(sessionRepository.existsByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> sessionService.createSession(userId, new CreateSessionRequest("mio")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_ALREADY_ACTIVE));
    }

    @Test
    @DisplayName("활성 세션 unique 제약 위반이면 SESSION_ALREADY_ACTIVE 예외로 변환한다")
    void createSession_uniqueViolation_throwsBusinessException() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(sessionRepository.existsByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(false);
        when(sessionRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("save failed", new RuntimeException("uq_sessions_one_active_per_user")));

        assertThatThrownBy(() -> sessionService.createSession(userId, new CreateSessionRequest("mio")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_ALREADY_ACTIVE));
    }

    @Test
    @DisplayName("다른 DB 무결성 예외는 그대로 전파한다")
    void createSession_otherIntegrityViolation_propagates() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(sessionRepository.existsByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(false);
        when(sessionRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("save failed", new RuntimeException("other_constraint")));

        assertThatThrownBy(() -> sessionService.createSession(userId, new CreateSessionRequest("mio")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("활성 세션이 없으면 getActiveSession은 session_id가 null인 응답을 반환한다")
    void getActiveSession_noSession_returnsNullSessionId() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        when(sessionRepository.findByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(Optional.empty());
        when(sessionRepository.findTopByUser_IdAndStatusOrderByEndedAtDesc(userId, SessionStatus.ENDED))
                .thenReturn(Optional.empty());

        ActiveSessionResponse response = sessionService.getActiveSession(userId);

        assertThat(response.sessionId()).isNull();
        assertThat(response.lastSummaryStatus()).isNull();
    }

    @Test
    @DisplayName("활성 세션이 있으면 getActiveSession은 세션 정보를 반환한다")
    void getActiveSession_hasSession_returnsSession() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        Session session = Session.builder()
                .user(mockUser)
                .characterId("mio")
                .build();
        when(sessionRepository.findByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(Optional.of(session));

        ActiveSessionResponse response = sessionService.getActiveSession(userId);

        assertThat(response.characterId()).isEqualTo("mio");
        assertThat(response.status()).isEqualTo("active");
    }

    @Test
    @DisplayName("온보딩 미완료 사용자가 활성 세션 조회 시 ONBOARDING_REQUIRED 예외가 발생한다")
    void getActiveSession_onboardingIncomplete_throws() {
        User incompleteUser = User.builder()
                .socialProvider("kakao")
                .socialId("test-id")
                .privacyConsent(true)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(incompleteUser));

        assertThatThrownBy(() -> sessionService.getActiveSession(userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_REQUIRED));
    }

    @Test
    @DisplayName("이미 종료된 세션을 종료하면 SESSION_ALREADY_ENDED 예외가 발생한다")
    void endSession_alreadyEnded_throws() {
        UUID sessionId = UUID.randomUUID();
        Session endedSession = Session.builder()
                .user(mockUser)
                .characterId("mio")
                .build();
        endedSession.end();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(endedSession));

        assertThatThrownBy(() -> sessionService.endSession(userId, sessionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_ALREADY_ENDED));
    }

    @Test
    @DisplayName("세션이 존재하지 않으면 SESSION_NOT_FOUND 예외가 발생한다")
    void endSession_notFound_throws() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.endSession(userId, sessionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("streamMessage는 ConversationOrchestrator에 위임한다")
    void streamMessage_delegates_to_orchestrator() {
        UUID sessionId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);

        sessionService.streamMessage(userId, sessionId, new SendMessageRequest("안녕"), emitter, null);

        verify(conversationOrchestrator).handle(userId, sessionId, "안녕", emitter);
    }

    @Test
    @DisplayName("validateMessageRequest: Rate Limit 초과 시 RATE_LIMIT_EXCEEDED 예외가 발생한다")
    void validateMessageRequest_rateLimitExceeded_throws() {
        when(valueOps.increment(anyString())).thenReturn(61L);
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder().user(mockUser).characterId("mio").build();
        ReflectionTestUtils.setField(session, "id", sessionId);
        lenient().when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.validateMessageRequest(userId, sessionId, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("validateMessageRequest: 중복 Idempotency-Key 시 DUPLICATE_REQUEST 예외가 발생한다")
    void validateMessageRequest_duplicateIdempotencyKey_throws() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder().user(mockUser).characterId("mio").build();
        ReflectionTestUtils.setField(session, "id", sessionId);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);

        assertThatThrownBy(() -> sessionService.validateMessageRequest(userId, sessionId, "dup-key-123"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_REQUEST));
    }

    @Test
    @DisplayName("validateMessageRequest: Idempotency-Key가 null이면 setIfAbsent를 호출하지 않는다")
    void validateMessageRequest_nullIdempotencyKey_skipsAtomicSet() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder().user(mockUser).characterId("mio").build();
        ReflectionTestUtils.setField(session, "id", sessionId);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        sessionService.validateMessageRequest(userId, sessionId, null);

        verify(redisTemplate).expire(anyString(), anyLong(), any());
    }
}
