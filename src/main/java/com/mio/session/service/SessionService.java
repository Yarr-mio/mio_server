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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final ConversationOrchestrator conversationOrchestrator;
    private final WorkingMemory workingMemory;
    private final ApplicationEventPublisher eventPublisher;
    private final ContextPreWarmer contextPreWarmer;

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
            // 세션 생성 직후 비동기 pre-warming (사용자 타이핑 5~30초 동안 캐싱)
            contextPreWarmer.preWarm(saved.getId(), userId);
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

        // Redis 정리 + SessionConsolidator 이벤트: 커밋 후 실행 — 커넥션 점유 최소화
        String characterId = session.getCharacterId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                workingMemory.clear(sessionId);
                eventPublisher.publishEvent(new SessionEndedEvent(sessionId, userId, characterId));
            }
        });

        return response;
    }

    public void streamMessage(UUID userId, UUID sessionId, SendMessageRequest request, SseEmitter emitter) {
        Session session = findSession(sessionId);
        validateSessionOwner(session, userId);
        conversationOrchestrator.handle(userId, sessionId, request.content(), emitter);
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
