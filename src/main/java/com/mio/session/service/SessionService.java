package com.mio.session.service;

import com.mio.ai.memory.consolidation.SessionEndedEvent;
import com.mio.ai.memory.working.WorkingMemory;
import com.mio.ai.orchestrator.ConversationOrchestrator;
import com.mio.ai.profile.ContextPreWarmer;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.MessageRole;
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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    private final SessionOpeningService sessionOpeningService;

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
            // saveAndFlush 인 이유: 아래 선제 인사 생성이 sessions 를 조회하므로 Hibernate 가
            // 그 시점에 이 INSERT 를 자동 flush 한다. 그러면 활성 세션 unique 위반이 조회
            // 호출부에서 터지고, 그 호출부가 조회 실패를 폴백 처리하면서 위반을 삼킨다.
            // 그 뒤 트랜잭션은 이미 abort 상태라 후속 문장이 전부 실패하고, 아래 catch 가
            // 잡지 못하는 예외로 끝나 409 대신 500 이 나간다. 여기서 명시적으로 flush 해서
            // 경합을 이 try 안에서 확정한다.
            Session saved = sessionRepository.saveAndFlush(session);

            // 선제 인사는 세션과 같은 트랜잭션에서 저장한다 (이슈 #530). 세션만 있고 인사가
            // 없는 중간 상태를 만들지 않기 위해서다. LLM 은 호출하지 않는다 — 사용자 발화
            // 전에는 안전·CBT 추론을 실행할 근거가 없다.
            InitialAssistantMessageResponse initialMessage =
                    sessionOpeningService.createOpening(saved, user);

            // pre-warming은 커밋 후 실행 — 트랜잭션 롤백 시 고아 캐시 방지
            UUID savedSessionId = saved.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        appendOpeningToWorkingMemory(savedSessionId, initialMessage);
                        contextPreWarmer.preWarm(savedSessionId, userId);
                    }
                });
            } else {
                appendOpeningToWorkingMemory(savedSessionId, initialMessage);
                contextPreWarmer.preWarm(savedSessionId, userId);
            }
            return SessionResponse.from(saved, initialMessage);
        } catch (DataIntegrityViolationException e) {
            if (isActiveSessionUniqueViolation(e)) {
                throw new BusinessException(ErrorCode.SESSION_ALREADY_ACTIVE);
            }
            throw e;
        }
    }

    /**
     * 오프닝을 세션 버퍼에 넣어 첫 턴 history 앞에 놓는다 (이슈 #530).
     *
     * <p>커밋 후에 실행한다 — 롤백된 세션의 발화가 Redis 에 남으면 다음 턴 프롬프트가 존재하지
     * 않는 대화를 참조한다. 실패해도 예외를 전파하지 않는다. 이미 커밋된 세션·인사를
     * 사용자에게 돌려주지 못하게 만드는 대가가, 첫 턴 문맥에서 인사 한 줄이 빠지는 것보다 크다.
     */
    private void appendOpeningToWorkingMemory(UUID sessionId, InitialAssistantMessageResponse opening) {
        try {
            workingMemory.appendMessage(sessionId, MessageRole.ASSISTANT.value(), opening.content());
        } catch (Exception e) {
            log.warn("Failed to append session opening to working memory: sessionId={}", sessionId, e);
        }
    }

    @Transactional(readOnly = true)
    public ActiveSessionResponse getActiveSession(UUID userId) {
        User user = findUser(userId);
        if (!user.getSignupStep().isOnboardingComplete()) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }
        return sessionRepository.findByUser_IdAndStatus(userId, SessionStatus.ACTIVE)
                .map(active -> ActiveSessionResponse.fromActive(
                        active,
                        // 저장된 인사를 그대로 돌려준다. 새로 만들지 않는다 — 재진입마다
                        // 인사가 늘어나면 대화창에 같은 말풍선이 쌓인다 (이슈 #530).
                        sessionOpeningService.findOpening(active.getId()).orElse(null)))
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
    public String acquireTurnLock(UUID sessionId, String idempotencyKey) {
        // 이미 완료된 턴의 재생 요청은 아무것도 바꾸지 않는다. 저장된 응답을 다시 보낼 뿐이라
        // 직렬화가 필요 없고, 다른 턴이 진행 중이라는 이유로 409 를 주면 재접속한 클라이언트가
        // 자기 응답을 받지 못한다.
        if (isCompletedTurnReplay(sessionId, idempotencyKey)) {
            return null;
        }
        String token = sessionMessageLock.tryAcquire(sessionId);
        if (token == null) {
            throw new BusinessException(ErrorCode.SESSION_MESSAGE_IN_PROGRESS);
        }
        return token;
    }

    private boolean isCompletedTurnReplay(UUID sessionId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return false;
        }
        return sessionMessagePersistenceService.findTurn(sessionId, idempotencyKey)
                .filter(turn -> turn.getStatus() == TurnStatus.COMPLETED)
                .isPresent();
    }

    public void streamMessage(UUID userId, UUID sessionId, SendMessageRequest request,
                              SseEmitter emitter, String idempotencyKey, String lockToken) {
        Thread renewer = startLeaseRenewer(sessionId, lockToken);
        try {
            conversationOrchestrator.handle(userId, sessionId, request.content(), emitter, idempotencyKey);
        } finally {
            if (renewer != null) {
                renewer.interrupt();
            }
            sessionMessageLock.release(sessionId, lockToken);
        }
    }

    /**
     * 처리가 도는 동안 락 임대를 연장한다 (이슈 #243 리뷰 반영).
     *
     * <p>임대만 길게 잡고 갱신하지 않으면, LLM 429 재시도가 겹쳐 한 턴이 임대보다 오래 걸릴 때
     * 락이 먼저 풀린다. 그러면 같은 세션의 다음 요청이 통과해 <b>직렬화가 조용히 깨진다</b> —
     * 이 PR 이 막으려는 상황이 실패 조건에서 그대로 재현되는 셈이다. SSE 타임아웃(60초)은
     * 클라이언트 스트림만 닫을 뿐 서버 처리를 중단시키지 않으므로 상한이 되지 못한다.
     *
     * <p>{@code TurnHeartbeat} 가 DB 턴 리스에 하는 것과 같은 방식이다.
     */
    private Thread startLeaseRenewer(UUID sessionId, String lockToken) {
        if (lockToken == null) {
            return null;
        }
        long intervalMs = SessionMessageLock.renewInterval().toMillis();
        return Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Thread.sleep(intervalMs);
                    if (!sessionMessageLock.renew(sessionId, lockToken)) {
                        // 이미 다른 요청이 같은 세션을 잡았다. 되돌릴 수는 없지만 사후에
                        // 확인할 수 있어야 한다.
                        log.error("SessionMessageLock: lease lost while turn was running sessionId={}",
                                sessionId);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
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
