package com.mio.session.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import com.mio.session.dto.ActiveSessionResponse;
import com.mio.session.dto.CreateSessionRequest;
import com.mio.session.dto.SendMessageRequest;
import com.mio.session.dto.SessionResponse;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private SessionMessagePersistenceService sessionMessagePersistenceService;

    private SessionService sessionService;
    private UUID userId;
    private User mockUser;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(
                sessionRepository, userRepository, sessionMessagePersistenceService, new ObjectMapper().findAndRegisterModules()
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
    @DisplayName("활성 세션이 없으면 getActiveSession은 Optional.empty를 반환한다")
    void getActiveSession_noSession_returnsNull() {
        when(sessionRepository.findByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(Optional.empty());

        Optional<ActiveSessionResponse> response = sessionService.getActiveSession(userId);

        assertThat(response).isEmpty();
    }

    @Test
    @DisplayName("활성 세션이 있으면 getActiveSession은 세션 정보를 반환한다")
    void getActiveSession_hasSession_returnsSession() {
        Session session = Session.builder()
                .user(mockUser)
                .characterId("mio")
                .build();
        when(sessionRepository.findByUser_IdAndStatus(userId, SessionStatus.ACTIVE)).thenReturn(Optional.of(session));

        Optional<ActiveSessionResponse> response = sessionService.getActiveSession(userId);

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().characterId()).isEqualTo("mio");
        assertThat(response.orElseThrow().status()).isEqualTo("active");
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
    @DisplayName("streamMessage는 사용자/어시스턴트 메시지를 한 번에 저장한다")
    void streamMessage_success_persistsConversationAtomically() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder()
                .user(mockUser)
                .characterId("mio")
                .build();
        ReflectionTestUtils.setField(session, "id", sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(userRepository.findById(userId)).thenReturn(Optional.of(mockUser));
        SseEmitter emitter = mock(SseEmitter.class);
        doNothing().when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        sessionService.streamMessage(userId, sessionId, new SendMessageRequest("안녕"), emitter);

        verify(sessionMessagePersistenceService).saveConversation(
                eq(sessionId),
                eq(userId),
                eq("안녕"),
                eq("안녕하세요! 오늘 어떤 이야기를 나눠볼까요?")
        );
        verify(emitter).complete();
    }
}
