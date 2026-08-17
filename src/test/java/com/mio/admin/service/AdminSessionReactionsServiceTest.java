package com.mio.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.admin.dto.SessionReactionsResponse;
import com.mio.ai.domain.CharacterInteraction;
import com.mio.ai.domain.CharacterInteractionRepository;
import com.mio.ai.domain.UserMemoryPreference;
import com.mio.ai.memory.episodic.InterventionOutcome;
import com.mio.ai.memory.episodic.InterventionOutcomeRepository;
import com.mio.ai.repository.UserMemoryPreferenceRepository;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.session.domain.Message;
import com.mio.session.domain.MessageRole;
import com.mio.session.domain.Session;
import com.mio.session.domain.SummaryStatus;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 이슈 #475 — 세션 반응 신호 7종(hotline_resource_tapped 제외) 조회 검증. */
@ExtendWith(MockitoExtension.class)
class AdminSessionReactionsServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private InterventionOutcomeRepository interventionOutcomeRepository;
    @Mock private UserMemoryPreferenceRepository userMemoryPreferenceRepository;
    @Mock private CharacterInteractionRepository characterInteractionRepository;
    @Mock private ProactiveCareLogRepository proactiveCareLogRepository;

    private AdminSessionReactionsService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime startedAt = OffsetDateTime.parse("2026-08-17T10:00:00+09:00");

    @BeforeEach
    void setUp() {
        service = new AdminSessionReactionsService(
                sessionRepository, messageRepository, interventionOutcomeRepository,
                userMemoryPreferenceRepository, characterInteractionRepository,
                proactiveCareLogRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("투두 반응·감정추이·요약조회·CBT완료사유를 세션·유저 데이터에서 채운다")
    void getReactions_aggregatesAllSignals() {
        User user = User.builder().id(userId).build();
        Session session = sessionWithCbtCompletionReason(user, "stabilized", SummaryStatus.VIEWED);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        when(interventionOutcomeRepository.findBySessionId(sessionId)).thenReturn(List.of(
                outcome("positive"), outcome("positive"), outcome("negative"), outcome("neutral")));

        when(userMemoryPreferenceRepository.findByUserId(userId)).thenReturn(Optional.of(
                UserMemoryPreference.builder().userId(userId).dislikedPatterns("[\"breathing_exercise\"]").build()));

        when(characterInteractionRepository.findByUserIdAndCharacterId(userId, "mio")).thenReturn(Optional.of(
                CharacterInteraction.builder().userId(userId).characterId("mio").affinityScore(0.7).build()));

        when(messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                messageWithEmotionScore(30), messageWithEmotionScore(null), messageWithEmotionScore(55)));

        when(proactiveCareLogRepository.findMostRecentBeforeSessionStart(eq(userId), any(), any(), any()))
                .thenReturn(List.of());

        SessionReactionsResponse response = service.getReactions(sessionId);

        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.todoReactionCounts().positive()).isEqualTo(2);
        assertThat(response.todoReactionCounts().negative()).isEqualTo(1);
        assertThat(response.todoReactionCounts().neutral()).isEqualTo(1);
        assertThat(response.dislikedPatterns()).containsExactly("breathing_exercise");
        assertThat(response.characterAffinityScore()).isEqualTo(0.7);
        assertThat(response.cbtCompletionReason()).isEqualTo("stabilized");
        assertThat(response.emotionTrend().startScore()).isEqualTo(30);
        assertThat(response.emotionTrend().endScore()).isEqualTo(55);
        assertThat(response.emotionTrend().delta()).isEqualTo(25);
        assertThat(response.summaryViewed()).isTrue();
        assertThat(response.notifiedBeforeSession()).isNull();
    }

    @Test
    @DisplayName("감정점수가 기록된 메시지가 하나도 없으면 감정추이는 전부 null이다(0으로 채우지 않음)")
    void getReactions_noScoredMessages_emotionTrendAllNull() {
        User user = User.builder().id(userId).build();
        Session session = sessionWithCbtCompletionReason(user, null, SummaryStatus.PENDING);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(interventionOutcomeRepository.findBySessionId(sessionId)).thenReturn(List.of());
        when(userMemoryPreferenceRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(characterInteractionRepository.findByUserIdAndCharacterId(userId, "mio")).thenReturn(Optional.empty());
        when(messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of(messageWithEmotionScore(null)));
        when(proactiveCareLogRepository.findMostRecentBeforeSessionStart(eq(userId), any(), any(), any()))
                .thenReturn(List.of());

        SessionReactionsResponse response = service.getReactions(sessionId);

        assertThat(response.emotionTrend().startScore()).isNull();
        assertThat(response.emotionTrend().endScore()).isNull();
        assertThat(response.emotionTrend().delta()).isNull();
        assertThat(response.characterAffinityScore()).isNull();
        assertThat(response.dislikedPatterns()).isEmpty();
        assertThat(response.summaryViewed()).isFalse();
    }

    @Test
    @DisplayName("세션 시작 전 발송된 알림을 열람했으면 notified_before_session이 true다")
    void getReactions_notificationOpened_notifiedTrue() {
        User user = User.builder().id(userId).build();
        Session session = sessionWithCbtCompletionReason(user, null, SummaryStatus.PENDING);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(interventionOutcomeRepository.findBySessionId(sessionId)).thenReturn(List.of());
        when(userMemoryPreferenceRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(characterInteractionRepository.findByUserIdAndCharacterId(userId, "mio")).thenReturn(Optional.empty());
        when(messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        ProactiveCareLog openedLog = ProactiveCareLog.builder()
                .user(user).triggerCode("checkin_reminder_morning")
                .notificationStatus(ProactiveCareLog.STATUS_OPENED)
                .build();
        when(proactiveCareLogRepository.findMostRecentBeforeSessionStart(eq(userId), any(), any(), any()))
                .thenReturn(List.of(openedLog));

        SessionReactionsResponse response = service.getReactions(sessionId);

        assertThat(response.notifiedBeforeSession()).isTrue();
    }

    @Test
    @DisplayName("세션 시작 전 발송된 알림이 열람 안 됐으면 notified_before_session이 false다")
    void getReactions_notificationSentNotOpened_notifiedFalse() {
        User user = User.builder().id(userId).build();
        Session session = sessionWithCbtCompletionReason(user, null, SummaryStatus.PENDING);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(interventionOutcomeRepository.findBySessionId(sessionId)).thenReturn(List.of());
        when(userMemoryPreferenceRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(characterInteractionRepository.findByUserIdAndCharacterId(userId, "mio")).thenReturn(Optional.empty());
        when(messageRepository.findBySession_IdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        ProactiveCareLog sentLog = ProactiveCareLog.builder()
                .user(user).triggerCode("checkin_reminder_morning")
                .notificationStatus(ProactiveCareLog.STATUS_SENT)
                .build();
        when(proactiveCareLogRepository.findMostRecentBeforeSessionStart(eq(userId), any(), any(), any()))
                .thenReturn(List.of(sentLog));

        SessionReactionsResponse response = service.getReactions(sessionId);

        assertThat(response.notifiedBeforeSession()).isFalse();
    }

    private Session sessionWithCbtCompletionReason(User user, String reason, SummaryStatus summaryStatus) {
        return Session.builder()
                .id(sessionId).user(user).characterId("mio").startedAt(startedAt)
                .summaryStatus(summaryStatus)
                .cbtCompletionReason(reason)
                .build();
    }

    private InterventionOutcome outcome(String reaction) {
        return InterventionOutcome.builder()
                .user(User.builder().id(userId).build())
                .sessionId(sessionId)
                .interventionKind("breathing_exercise")
                .userReaction(reaction)
                .build();
    }

    private Message messageWithEmotionScore(Integer score) {
        return Message.builder()
                .id(UUID.randomUUID())
                .role(MessageRole.USER)
                .contentCiphertext(new byte[]{1})
                .contentDekId("dek")
                .emotionScore(score)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}
