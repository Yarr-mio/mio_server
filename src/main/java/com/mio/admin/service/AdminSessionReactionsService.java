package com.mio.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.admin.dto.SessionReactionsResponse;
import com.mio.ai.domain.CharacterInteraction;
import com.mio.ai.domain.CharacterInteractionRepository;
import com.mio.ai.domain.UserMemoryPreference;
import com.mio.ai.memory.episodic.InterventionOutcome;
import com.mio.ai.memory.episodic.InterventionOutcomeRepository;
import com.mio.ai.repository.UserMemoryPreferenceRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.session.domain.Message;
import com.mio.session.domain.Session;
import com.mio.session.domain.SummaryStatus;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 운영자용 세션 반응 신호 조회 (이슈 #475, 개선안 문서 §4.1~§4.3).
 *
 * <p>새 UI·신규 계측 없이 기존 테이블만으로 뽑을 수 있는 반응 신호 9개 중 7개를 담당한다.
 * 위기트리거 반응 2개(continued_engagement, hotline_resource_tapped)는 Track1 성격이라
 * {@link AdminSessionService}의 세션조회 API 쪽에서 다룬다 — hotline_resource_tapped는 백엔드에
 * 캡처 경로가 없어(핫라인 자원은 SSE로만 나가고 탭 이벤트를 받는 곳이 없음) 이번 스코프에서 뺐다.
 *
 * <p>세션 단위 원문(대화 내용)은 노출하지 않는다 — 숫자·enum·bool 신호만 반환해 Track2의
 * 가명·집계 원칙과 충돌하지 않게 한다(§4.3 리뷰 반영).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminSessionReactionsService {

    /** 세션 시작 전 이 기간 안에 발송된 알림까지만 "이 세션을 유발했을 수 있다"고 본다. */
    private static final Duration NOTIFICATION_LOOKBACK = Duration.ofHours(24);

    /** 이슈 #476 — 세션 종료 후 이 기간 안에 재방문했는지를 리텐션 신호로 본다. */
    private static final int RETENTION_WINDOW_DAYS = 7;

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final InterventionOutcomeRepository interventionOutcomeRepository;
    private final UserMemoryPreferenceRepository userMemoryPreferenceRepository;
    private final CharacterInteractionRepository characterInteractionRepository;
    private final ProactiveCareLogRepository proactiveCareLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SessionReactionsResponse getReactions(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        UUID userId = session.getUser().getId();

        return new SessionReactionsResponse(
                sessionId,
                userId,
                todoReactionCounts(sessionId),
                dislikedPatterns(userId),
                characterAffinityScore(userId, session.getCharacterId()),
                session.getCbtCompletionReason(),
                emotionTrend(sessionId),
                session.getSummaryStatus() == SummaryStatus.VIEWED,
                notifiedBeforeSession(userId, session.getStartedAt()),
                returnedWithin7Days(userId, session)
        );
    }

    private SessionReactionsResponse.TodoReactionCounts todoReactionCounts(UUID sessionId) {
        List<InterventionOutcome> outcomes = interventionOutcomeRepository.findBySessionId(sessionId);
        long positive = outcomes.stream().filter(o -> "positive".equals(o.getUserReaction())).count();
        long negative = outcomes.stream().filter(o -> "negative".equals(o.getUserReaction())).count();
        long neutral = outcomes.stream().filter(o -> "neutral".equals(o.getUserReaction())).count();
        return new SessionReactionsResponse.TodoReactionCounts(positive, negative, neutral);
    }

    private List<String> dislikedPatterns(UUID userId) {
        UserMemoryPreference pref = userMemoryPreferenceRepository.findByUserId(userId).orElse(null);
        if (pref == null || pref.getDislikedPatterns() == null || pref.getDislikedPatterns().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(pref.getDislikedPatterns(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse disliked_patterns for userId={}", userId, e);
            return List.of();
        }
    }

    private Double characterAffinityScore(UUID userId, String characterId) {
        return characterInteractionRepository.findByUserIdAndCharacterId(userId, characterId)
                .map(CharacterInteraction::getAffinityScore)
                .orElse(null);
    }

    private SessionReactionsResponse.EmotionTrend emotionTrend(UUID sessionId) {
        List<Integer> scores = messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId).stream()
                .map(Message::getEmotionScore)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (scores.isEmpty()) {
            return new SessionReactionsResponse.EmotionTrend(null, null, null);
        }
        Integer start = scores.get(0);
        Integer end = scores.get(scores.size() - 1);
        return new SessionReactionsResponse.EmotionTrend(start, end, end - start);
    }

    /**
     * @return 세션 시작 {@link #NOTIFICATION_LOOKBACK} 이내에 발송된 알림이 없으면 {@code null}
     *         (신호 없음 — 0으로 채우지 않는다), 있으면 열람(tapped) 여부
     */
    private Boolean notifiedBeforeSession(UUID userId, OffsetDateTime sessionStartedAt) {
        List<ProactiveCareLog> logs = proactiveCareLogRepository.findMostRecentBeforeSessionStart(
                userId, sessionStartedAt.minus(NOTIFICATION_LOOKBACK), sessionStartedAt, PageRequest.of(0, 1));
        if (logs.isEmpty()) {
            return null;
        }
        return ProactiveCareLog.STATUS_OPENED.equals(logs.get(0).getNotificationStatus());
    }

    /**
     * @return 세션이 아직 안 끝났으면 {@code null}(신호 없음), 끝났으면 그 뒤 7일 안에 이
     *         유저의 다른 세션이 시작됐는지(이슈 #476)
     */
    private Boolean returnedWithin7Days(UUID userId, Session session) {
        if (session.getEndedAt() == null) {
            return null;
        }
        return sessionRepository.existsSessionStartedInWindow(
                userId, session.getEndedAt(), session.getEndedAt().plusDays(RETENTION_WINDOW_DAYS));
    }
}
