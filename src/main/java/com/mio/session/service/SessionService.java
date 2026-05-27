package com.mio.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import com.mio.session.dto.*;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionService {

    private static final Set<String> ALLOWED_CHARACTER_IDS = Set.of("mio", "bau", "rumi", "momo", "chichi");

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final SessionMessagePersistenceService sessionMessagePersistenceService;
    private final ObjectMapper objectMapper;

    @Transactional
    public SessionResponse createSession(UUID userId, CreateSessionRequest request) {
        User user = findUser(userId);

        if (!user.getSignupStep().isOnboardingComplete()) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }

        String characterId = (request.characterId() != null && !request.characterId().isBlank())
                ? request.characterId()
                : user.getPreferredCharacterId();

        if (!ALLOWED_CHARACTER_IDS.contains(characterId)) {
            throw new BusinessException(ErrorCode.INVALID_CHARACTER_ID);
        }

        if (sessionRepository.existsByUser_IdAndStatus(userId, SessionStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_ACTIVE);
        }

        Session session = Session.builder()
                .user(user)
                .characterId(characterId)
                .build();

        try {
            return SessionResponse.from(sessionRepository.save(session));
        } catch (DataIntegrityViolationException e) {
            if (isActiveSessionUniqueViolation(e)) {
                throw new BusinessException(ErrorCode.SESSION_ALREADY_ACTIVE);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Optional<ActiveSessionResponse> getActiveSession(UUID userId) {
        return sessionRepository.findByUser_IdAndStatus(userId, SessionStatus.ACTIVE)
                .map(ActiveSessionResponse::from);
    }

    @Transactional
    public EndSessionResponse endSession(UUID userId, UUID sessionId) {
        Session session = findSession(sessionId);
        if (!session.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (session.isEnded()) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_ENDED);
        }
        session.end();
        return EndSessionResponse.from(sessionRepository.save(session));
    }

    public void streamMessage(UUID userId, UUID sessionId, SendMessageRequest request, SseEmitter emitter) {
        try {
            Session session = findSession(sessionId);
            validateSessionOwner(session, userId);
            User user = findUser(userId);

            String inboundMsgId = "msg_in_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String outboundMsgId = "msg_out_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String assistantReply = "안녕하세요! 오늘 어떤 이야기를 나눠볼까요?";

            // session_meta 이벤트
            sendEvent(emitter, new SseEventDto.SessionMetaEvent(inboundMsgId, OffsetDateTime.now(ZoneOffset.UTC)));

            // 사용자/어시스턴트 메시지를 한 트랜잭션으로 저장
            sessionMessagePersistenceService.saveConversation(session.getId(), user.getId(), request.content(), assistantReply);

            // stub: delta 이벤트 1개
            sendEvent(emitter, new SseEventDto.DeltaEvent(assistantReply, outboundMsgId));

            // done 이벤트
            sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId, null, false, "stop"));

            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void sendEvent(SseEmitter emitter, SseEventDto event) throws IOException {
        String json = toJson(event);
        emitter.send(SseEmitter.event()
                .name(event.eventName())
                .data(json));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private Session findSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    private void validateSessionOwner(Session session, UUID userId) {
        if (!Objects.equals(session.getUser().getId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (session.isEnded()) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_ENDED);
        }
    }

    private boolean isActiveSessionUniqueViolation(DataIntegrityViolationException e) {
        Throwable mostSpecificCause = NestedExceptionUtils.getMostSpecificCause(e);
        return mostSpecificCause != null
                && mostSpecificCause.getMessage() != null
                && mostSpecificCause.getMessage().contains("uq_sessions_one_active_per_user");
    }
}
