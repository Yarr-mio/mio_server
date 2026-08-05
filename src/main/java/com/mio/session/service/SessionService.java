package com.mio.session.service;

import com.mio.ai.memory.consolidation.SessionEndedEvent;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.orchestrator.ConversationOrchestrator;
import com.mio.ai.profile.ContextPreWarmer;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import com.mio.session.domain.SummaryStatus;
import com.mio.session.domain.TurnStatus;
import com.mio.session.dto.*;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.todo.repository.BehaviorTaskRepository;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SessionService {

    private static final Set<String> ALLOWED_CHARACTER_IDS = Set.of("mio", "bau", "rumi", "momo", "chichi");
    private static final int MSG_RATE_LIMIT_MAX = 60;
    private static final long MSG_RATE_LIMIT_TTL_SECONDS = 60L;
    /**
     * 이 시간 안에 갱신된 generating 턴은 아직 살아 있다고 본다.
     *
     * <p>SSE emitter 타임아웃(60초)과 Nginx proxy_read_timeout(60초)보다 길게 잡는다. 그보다
     * 짧으면 실제로 응답을 만들고 있는 턴을 버려진 것으로 오인해 중복 생성이 일어난다.
     */
    private static final Duration IN_FLIGHT_TURN_WINDOW = Duration.ofSeconds(90);

    private final SessionRepository sessionRepository;
    private final SessionSummaryRepository sessionSummaryRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final UserRepository userRepository;
    private final SessionMessagePersistenceService sessionMessagePersistenceService;
    private final ConversationOrchestrator conversationOrchestrator;
    private final SessionMessageLock sessionMessageLock;
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
    public ActiveSessionResponse getActiveSession(UUID userId) {
        User user = findUser(userId);
        if (!user.getSignupStep().isOnboardingComplete()) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }
        return sessionRepository.findByUser_IdAndStatus(userId, SessionStatus.ACTIVE)
                .map(ActiveSessionResponse::fromActive)
                .orElseGet(() -> {
                    Session lastEnded = sessionRepository
                            .findTopByUser_IdAndStatusOrderByEndedAtDesc(userId, SessionStatus.ENDED)
                            .orElse(null);
                    return ActiveSessionResponse.noActiveSession(lastEnded);
                });
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
        // clear() 이전에 읽어야 race condition 방지 (async consolidator보다 clear()가 먼저 실행될 수 있음)
        int socraticCount = workingMemory.getSocraticQuestionCount(sessionId);
        eventPublisher.publishEvent(new SessionEndedEvent(sessionId, userId, characterId, socraticCount));

        // Redis 정리는 커밋 후 실행 (트랜잭션 불필요)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workingMemory.clear(sessionId);
            }
        });

        return response;
    }

    @Transactional
    public SessionSummaryResponse getSessionSummary(UUID userId, UUID sessionId) {
        Session session = findSession(sessionId);
        if (!session.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!session.isEnded()) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        SummaryStatus status = session.getSummaryStatus();
        if (status == SummaryStatus.FAILED) {
            throw new BusinessException(ErrorCode.SESSION_SUMMARY_FAILED);
        }
        if (status == SummaryStatus.PENDING) {
            return SessionSummaryResponse.pending(session);
        }
        if (status == SummaryStatus.DONE) {
            session.markSummaryViewed();
            sessionRepository.save(session);
        }
        var summary = sessionSummaryRepository.findBySession_Id(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        var todos = behaviorTaskRepository.findBySourceSession_Id(sessionId);
        return SessionSummaryResponse.from(session, summary, todos);
    }

    /**
     * SSE 스트림 시작 전 동기 검증 — 여기서 예외 발생 시 HTTP 4xx로 응답됨.
     * 세션 검증도 여기서 수행하여 가상 스레드 내 SseEmitter 누수를 방지한다.
     */
    public void validateMessageRequest(UUID userId, UUID sessionId, String idempotencyKey) {
        checkMessageRateLimit(userId);
        Session session = findSession(sessionId);
        validateSessionOwner(session, userId);
        rejectIfTurnInFlight(sessionId, idempotencyKey);
    }

    /**
     * 같은 Idempotency-Key 의 턴이 <b>지금 진행 중</b>일 때만 거절한다 (이슈 P0-A).
     *
     * <p>이전에는 Redis 에 키를 선점하고 해제하지 않아, 첫 시도가 LLM 오류로 죽으면 TTL 1시간
     * 동안 같은 키로 재시도조차 막혔다. 이제 판정 근거를 턴 상태로 옮긴다.
     *
     * <ul>
     *   <li>진행 중 — 아직 살아 있을 수 있으므로 거절한다</li>
     *   <li>완료 — 저장된 응답을 재생하므로 통과시킨다</li>
     *   <li>실패·버려짐 — 같은 턴을 재개하므로 통과시킨다</li>
     * </ul>
     */
    private void rejectIfTurnInFlight(UUID sessionId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return;
        }
        sessionMessagePersistenceService.findTurn(sessionId, idempotencyKey)
                .filter(turn -> turn.getStatus() == TurnStatus.GENERATING)
                .filter(turn -> turn.getUpdatedAt().isAfter(
                        OffsetDateTime.now(ZoneOffset.UTC).minus(IN_FLIGHT_TURN_WINDOW)))
                .ifPresent(turn -> {
                    throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
                });
    }

    @Transactional
    public EmotionScoreResponse submitEmotionScore(UUID userId, UUID sessionId, EmotionScoreRequest request) {
        Session session = findSession(sessionId);
        if (!session.belongsTo(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!session.isEnded()) {
            throw new BusinessException(ErrorCode.SESSION_NOT_ENDED);
        }
        session.updateEmotionScoreUser(request.score());
        return EmotionScoreResponse.from(sessionRepository.save(session));
    }

    /**
     * 같은 세션의 턴을 하나씩 처리하도록 락을 잡는다 (이슈 #243).
     *
     * <p>요청마다 독립 virtual thread 에서 실행되므로, 락이 없으면 같은 세션의 LLM 응답 생성과
     * 메시지 저장·WorkingMemory 갱신이 서로 경쟁한다. 대화 순서가 뒤섞이거나 한쪽 턴이 다른
     * 쪽의 세션 상태를 덮어쓴다.
     *
     * <p><b>SSE 스트림을 열기 전에</b> 호출해야 한다. 스트림이 시작된 뒤에는 상태 코드를 바꿀 수
     * 없어, 사용자는 이유를 알 수 없는 끊긴 스트림만 보게 된다.
     */
    public String acquireTurnLock(UUID sessionId) {
        String token = sessionMessageLock.tryAcquire(sessionId);
        if (token == null) {
            throw new BusinessException(ErrorCode.SESSION_MESSAGE_IN_PROGRESS);
        }
        return token;
    }

    public void streamMessage(UUID userId, UUID sessionId, SendMessageRequest request,
                              SseEmitter emitter, String idempotencyKey, String lockToken) {
        try {
            conversationOrchestrator.handle(userId, sessionId, request.content(), emitter, idempotencyKey);
        } finally {
            sessionMessageLock.release(sessionId, lockToken);
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
