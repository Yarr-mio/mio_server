package com.mio.session.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Message;
import com.mio.session.domain.Session;
import com.mio.session.dto.*;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageEncryptor messageEncryptor;
    private final ObjectMapper objectMapper;

    @Transactional
    public SessionResponse createSession(UUID userId, CreateSessionRequest request) {
        User user = findUser(userId);

        if (!"ONBOARDING_COMPLETED".equals(user.getSignupStep())
                && !"COMPLETED".equals(user.getSignupStep())) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }

        String characterId = (request.characterId() != null && !request.characterId().isBlank())
                ? request.characterId()
                : user.getPreferredCharacterId();

        Session session = Session.builder()
                .user(user)
                .characterId(characterId)
                .build();

        return SessionResponse.from(sessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public ActiveSessionResponse getActiveSession(UUID userId) {
        return sessionRepository.findByUser_IdAndStatus(userId, "active")
                .map(ActiveSessionResponse::from)
                .orElse(null);
    }

    @Transactional
    public EndSessionResponse endSession(UUID userId, UUID sessionId) {
        Session session = findSession(sessionId);
        if ("ended".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_ENDED);
        }
        if (!java.util.Objects.equals(session.getUser().getId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        session.end();
        return EndSessionResponse.from(sessionRepository.save(session));
    }

    public void streamMessage(UUID userId, UUID sessionId, SendMessageRequest request, SseEmitter emitter) {
        try {
            Session session = findSessionTransactional(sessionId);
            validateSessionOwner(session, userId);

            String inboundMsgId = "msg_in_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String outboundMsgId = "msg_out_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

            // session_meta 이벤트
            sendEvent(emitter, new SseEventDto.SessionMetaEvent(inboundMsgId, OffsetDateTime.now()));

            // 사용자 메시지 저장
            saveMessage(session, findUser(userId), "user", request.content());

            // stub: delta 이벤트 1개
            sendEvent(emitter, new SseEventDto.DeltaEvent("안녕하세요! 오늘 어떤 이야기를 나눠볼까요?", outboundMsgId));

            // stub: 어시스턴트 메시지 저장
            saveMessage(session, findUser(userId), "assistant", "안녕하세요! 오늘 어떤 이야기를 나눠볼까요?");

            // done 이벤트
            sendEvent(emitter, new SseEventDto.DoneEvent(outboundMsgId, null, false, "stop"));

            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void saveMessage(Session session, User user, String role, String content) {
        byte[] ciphertext = messageEncryptor.encrypt(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Message message = Message.builder()
                .session(session)
                .user(user)
                .role(role)
                .contentCiphertext(ciphertext)
                .contentDekId(messageEncryptor.dekId())
                .isCrisisFlagged(false)
                .build();
        messageRepository.save(message);

        // 세션 카운터 갱신은 별도 트랜잭션에서 처리 (SSE는 비동기 컨텍스트)
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

    private Session findSessionTransactional(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    private void validateSessionOwner(Session session, UUID userId) {
        if ("ended".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.SESSION_ALREADY_ENDED);
        }
        if (!java.util.Objects.equals(session.getUser().getId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
