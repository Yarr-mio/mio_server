package com.mio.session.service;

import com.mio.ai.memory.consolidation.SessionEndedEvent;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.orchestrator.ConversationOrchestrator;
import com.mio.ai.profile.ContextPreWarmer;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import com.mio.session.dto.*;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SessionService {

    private static final Set<String> ALLOWED_CHARACTER_IDS = Set.of("mio", "bau", "rumi", "momo", "chichi");
    private static final int MSG_RATE_LIMIT_MAX = 60;
    private static final long MSG_RATE_LIMIT_TTL_SECONDS = 60L;
    private static final long MSG_IDEMPOTENCY_TTL_SECONDS = 3600L;

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final SessionMessagePersistenceService sessionMessagePersistenceService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final WorkingMemory workingMemory;
    private final ApplicationEventPublisher eventPublisher;
    private final ContextPreWarmer contextPreWarmer;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public SessionResponse createSession(UUID userId, CreateSessionRequest request) {
        User user = findUser(userId);

        if (!user.getSignupStep().isOnboardingComplete()) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }

        String characterId = (request.characterId() != null && !request.characterId().isBlank())
                ? request.characterId()
                : user.getPreferredCharacterId();

        if (characterId == null || !ALLOWED_CHARACTER_IDS.contains(characterId)) {
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
            Session saved = sessionRepository.save(session);
            // pre-warming은 커밋 후 실행 — 트랜잭션 롤백 시 고아 캐시 방지
            UUID savedSessionId = saved.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        contextPreWarmer.preWarm(savedSessionId, userId);
                    }
                });
            } else {
                contextPreWarmer.preWarm(savedSessionId, userId);
            }
            return SessionResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            if (isActiveSessionUniqueViolation(e)) {
                throw new BusinessException(ErrorCode.SESSION_ALREADY_ACTIVE);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Optional<ActiveSessionResponse> getActiveSession(UUID userId) {
        User user = findUser(userId);
        if (!user.getSignupStep().isOnboardingComplete()) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }
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
        EndSessionResponse response = EndSessionResponse.from(sessionRepository.save(session));

        // @TransactionalEventListener(AFTER_COMMIT) 캡처용: 트랜잭션 내 발행해야 커밋 후 리스너 실행됨
        String characterId = session.getCharacterId();
        eventPublisher.publishEvent(new SessionEndedEvent(sessionId, userId, characterId));

        // Redis 정리는 커밋 후 실행 (트랜잭션 불필요)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workingMemory.clear(sessionId);
            }
        });

        return response;
    }

    /**
     * SSE 스트림 시작 전 동기 검증 — 여기서 예외 발생 시 HTTP 4xx로 응답됨
     */
    public void validateMessageRequest(UUID userId, String idempotencyKey) {
        checkMessageRateLimit(userId);
        if (idempotencyKey != null
                && Boolean.TRUE.equals(redisTemplate.hasKey(messageIdempotencyKey(idempotencyKey)))) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
        }
    }

    public void streamMessage(UUID userId, UUID sessionId, SendMessageRequest request,
                              SseEmitter emitter, String idempotencyKey) {
        Session session = findSession(sessionId);
        validateSessionOwner(session, userId);
        conversationOrchestrator.handle(userId, sessionId, request.content(), emitter);
        if (idempotencyKey != null) {
            redisTemplate.opsForValue().set(
                    messageIdempotencyKey(idempotencyKey), "1", MSG_IDEMPOTENCY_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void checkMessageRateLimit(UUID userId) {
        String key = "session:ratelimit:msg:" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) return;
        if (count == 1) {
            redisTemplate.expire(key, MSG_RATE_LIMIT_TTL_SECONDS, TimeUnit.SECONDS);
        }
        if (count > MSG_RATE_LIMIT_MAX) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    private static String messageIdempotencyKey(String key) {
        return "msg:idempotency:" + key;
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
