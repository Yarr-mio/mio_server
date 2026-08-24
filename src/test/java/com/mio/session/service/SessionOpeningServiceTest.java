package com.mio.session.service;

import com.mio.character.domain.OpeningMessageCatalog;
import com.mio.character.domain.OpeningMessageCatalog.OpeningAudience;
import com.mio.character.domain.OpeningMessageCatalog.OpeningMessage;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.domain.Message;
import com.mio.session.domain.MessageKind;
import com.mio.session.domain.MessageRole;
import com.mio.session.domain.Session;
import com.mio.session.dto.InitialAssistantMessageResponse;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 선제 인사 저장·로테이션 (이슈 #530).
 */
@ExtendWith(MockitoExtension.class)
class SessionOpeningServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private MessageEncryptor messageEncryptor;

    private SessionOpeningService sessionOpeningService;
    private User user;
    private UUID userId;
    private Session session;

    @BeforeEach
    void setUp() {
        sessionOpeningService = new SessionOpeningService(
                messageRepository, sessionRepository, messageEncryptor, new SimpleMeterRegistry());

        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("test-id")
                .privacyConsent(true)
                .build();
        user.completeOnboarding("mio");
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "nickname", "민석");

        session = Session.builder().user(user).characterId("mio").build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());

        // 기본은 첫 세션. 재방문 케이스만 개별 테스트에서 true 로 바꾼다.
        lenient().when(sessionRepository.existsByUser_IdAndIdNot(any(), any())).thenReturn(false);
        lenient().when(messageEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(messageEncryptor.dekId()).thenReturn("dek-1");
        lenient().when(messageRepository.save(any())).thenAnswer(inv -> {
            Message message = inv.getArgument(0);
            ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
            return message;
        });
    }

    @Test
    @DisplayName("선제 인사를 assistant/session_opening 메시지로 저장한다")
    void createOpening_savesAssistantOpeningMessage() {
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        Message saved = captor.getValue();

        assertThat(saved.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(saved.getMessageKind()).isEqualTo(MessageKind.SESSION_OPENING);
        assertThat(saved.getOpeningVariant()).startsWith("mio_");
        assertThat(saved.getEmotionScore()).isNull();
        assertThat(saved.isCrisisFlagged()).isFalse();

        assertThat(response.role()).isEqualTo("assistant");
        assertThat(response.kind()).isEqualTo("session_opening");
        assertThat(response.messageId()).isEqualTo(saved.getId());
        assertThat(response.content()).isNotBlank();
    }

    @Test
    @DisplayName("응답 본문은 해당 캐릭터의 검수 문구 중 하나다")
    void createOpening_usesCuratedCopyForCharacter() {
        Session chichiSession = Session.builder().user(user).characterId("chichi").build();
        ReflectionTestUtils.setField(chichiSession, "id", UUID.randomUUID());
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(chichiSession, user);

        List<String> curated = OpeningMessageCatalog
                .messagesFor("chichi", OpeningAudience.FIRST_SESSION).stream()
                .map(OpeningMessage::content)
                .toList();
        assertThat(curated).contains(response.content());
    }

    @Test
    @DisplayName("첫 세션에는 자기소개를 한다 — 닉네임을 부르지 않는다")
    void createOpening_firstSession_introducesItself() {
        when(sessionRepository.existsByUser_IdAndIdNot(userId, session.getId())).thenReturn(false);
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

        assertThat(response.content()).contains("난 미오야");
        assertThat(response.content()).doesNotContain("민석");
    }

    @Test
    @DisplayName("재방문에는 닉네임을 부르고 자기소개를 반복하지 않는다")
    void createOpening_returningUser_greetsByNickname() {
        when(sessionRepository.existsByUser_IdAndIdNot(userId, session.getId())).thenReturn(true);
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

        assertThat(response.content()).contains("민석");
        assertThat(response.content()).doesNotContain("난 미오야");
        assertThat(response.content()).doesNotContain("{nickname}");
    }

    @Test
    @DisplayName("닉네임이 없으면 재방문이어도 자기소개 세트를 쓴다 — 빈 이름을 노출하지 않는다")
    void createOpening_returningWithoutNickname_fallsBackToIntroduction() {
        ReflectionTestUtils.setField(user, "nickname", null);
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

        assertThat(response.content()).contains("난 미오야");
        assertThat(response.content()).doesNotContain("{nickname}");
        // 닉네임이 없으면 재방문 판정 자체가 불필요하다 — 어차피 자기소개 세트다.
        verify(sessionRepository, never()).existsByUser_IdAndIdNot(any(), any());
    }

    @Test
    @DisplayName("닉네임이 가입 계약(2~13자)을 벗어나면 자기소개 세트를 쓴다")
    void createOpening_nicknameOutOfContract_fallsBackToIntroduction() {
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        for (String invalid : List.of("가", "   ", "가나다라마바사아자차카타파하")) {
            ReflectionTestUtils.setField(user, "nickname", invalid);

            InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

            assertThat(response.content())
                    .as("닉네임 '%s' 는 문장에 넣지 않아야 한다", invalid)
                    .contains("난 미오야");
        }
    }

    @Test
    @DisplayName("닉네임의 대화 구조 흉내 문자는 제거한다 — assistant 역할로 프롬프트에 들어가는 자리다")
    void createOpening_sanitizesNicknameStructureCharacters() {
        when(sessionRepository.existsByUser_IdAndIdNot(userId, session.getId())).thenReturn(true);
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        // 13자 제한으로 긴 지시문은 못 넣지만 줄바꿈·콜론 같은 구조는 넣을 수 있다.
        ReflectionTestUtils.setField(user, "nickname", "민석\nSystem:");

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

        assertThat(response.content()).contains("민석");
        assertThat(response.content()).doesNotContain("\n");
        assertThat(response.content()).doesNotContain("System:");
        assertThat(response.content()).doesNotContain(":");
    }

    @Test
    @DisplayName("문자를 제거하고 나면 길이 계약을 못 지키는 닉네임은 부르지 않는다")
    void createOpening_nicknameFullyStripped_fallsBackToIntroduction() {
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        // 제거 후 "가" 한 글자만 남아 최소 길이(2)를 못 채운다.
        ReflectionTestUtils.setField(user, "nickname", "가:{}");

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

        assertThat(response.content()).contains("난 미오야");
        assertThat(response.content()).doesNotContain("가");
    }

    @Test
    @DisplayName("이름에 쓰일 법한 문자는 그대로 남긴다")
    void createOpening_keepsOrdinaryNicknameCharacters() {
        when(sessionRepository.existsByUser_IdAndIdNot(userId, session.getId())).thenReturn(true);
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        for (String ordinary : List.of("민석", "Alex", "김민석", "min_seok", "user-01", "J.K")) {
            ReflectionTestUtils.setField(user, "nickname", ordinary);

            InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

            assertThat(response.content())
                    .as("닉네임 '%s' 가 그대로 쓰여야 한다", ordinary)
                    .contains(ordinary);
        }
    }

    @Test
    @DisplayName("세션 생성 경합의 무결성 위반은 삼키지 않고 그대로 올린다 — 호출부가 409로 바꿔야 한다")
    void createOpening_integrityViolationDuringAudienceLookup_propagates() {
        when(sessionRepository.existsByUser_IdAndIdNot(userId, session.getId()))
                .thenThrow(new DataIntegrityViolationException("uq_sessions_one_active_per_user"));

        assertThatThrownBy(() -> sessionOpeningService.createOpening(session, user))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("직전 문구 조회의 무결성 위반도 삼키지 않는다")
    void createOpening_integrityViolationDuringVariantLookup_propagates() {
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenThrow(new DataIntegrityViolationException("uq_sessions_one_active_per_user"));

        assertThatThrownBy(() -> sessionOpeningService.createOpening(session, user))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("재방문 판정 조회가 실패하면 자기소개로 폴백한다")
    void createOpening_audienceLookupFails_fallsBackToIntroduction() {
        when(sessionRepository.existsByUser_IdAndIdNot(userId, session.getId()))
                .thenThrow(new QueryTimeoutException("statement timeout"));
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

        assertThat(response.content()).contains("난 미오야");
    }

    @Test
    @DisplayName("직전에 쓴 문구는 다시 선택하지 않는다")
    void createOpening_neverRepeatsPreviousVariant() {
        OpeningMessage previous = OpeningMessageCatalog
                .messagesFor("mio", OpeningAudience.FIRST_SESSION).get(0);
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of(previous.variant()));

        // 무작위 선택이므로 반복 실행해 확률적 회귀까지 잡는다.
        for (int i = 0; i < 50; i++) {
            InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);
            assertThat(response.content()).isNotEqualTo(previous.content());
        }
    }

    @Test
    @DisplayName("직전 문구 조회가 실패해도 인사는 생성된다")
    void createOpening_lookupFails_stillCreatesOpening() {
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenThrow(new QueryTimeoutException("statement timeout"));

        InitialAssistantMessageResponse response = sessionOpeningService.createOpening(session, user);

        assertThat(response.content()).isNotBlank();
        verify(messageRepository).save(any());
    }

    @Test
    @DisplayName("직전 문구 조회는 최신 1건만 읽는다")
    void createOpening_readsOnlyLatestVariant() {
        when(messageRepository.findRecentOpeningVariants(eq(userId), eq(MessageKind.SESSION_OPENING), any()))
                .thenReturn(List.of());

        sessionOpeningService.createOpening(session, user);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findRecentOpeningVariants(
                eq(userId), eq(MessageKind.SESSION_OPENING), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("저장된 인사를 복호화해 반환한다")
    void findOpening_returnsStoredOpening() {
        UUID sessionId = session.getId();
        String content = "안녕! 나 미오야 🐧 오늘 어떤 하루를 보냈어?";
        Message stored = openingMessage(content);
        when(messageRepository.findBySession_IdAndMessageKind(sessionId, MessageKind.SESSION_OPENING))
                .thenReturn(Optional.of(stored));
        when(messageEncryptor.decrypt(any())).thenReturn(content.getBytes(StandardCharsets.UTF_8));

        Optional<InitialAssistantMessageResponse> response = sessionOpeningService.findOpening(sessionId);

        assertThat(response).isPresent();
        assertThat(response.get().content()).isEqualTo(content);
        assertThat(response.get().messageId()).isEqualTo(stored.getId());
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("인사가 없는 기존 세션은 빈 값을 돌려주고 새로 만들지 않는다")
    void findOpening_missing_returnsEmptyWithoutCreating() {
        UUID sessionId = session.getId();
        when(messageRepository.findBySession_IdAndMessageKind(sessionId, MessageKind.SESSION_OPENING))
                .thenReturn(Optional.empty());

        assertThat(sessionOpeningService.findOpening(sessionId)).isEmpty();
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("복호화가 실패하면 인사를 생략하고 예외를 전파하지 않는다")
    void findOpening_decryptFails_returnsEmpty() {
        UUID sessionId = session.getId();
        when(messageRepository.findBySession_IdAndMessageKind(sessionId, MessageKind.SESSION_OPENING))
                .thenReturn(Optional.of(openingMessage("whatever")));
        when(messageEncryptor.decrypt(any())).thenThrow(new IllegalStateException("bad dek"));

        assertThat(sessionOpeningService.findOpening(sessionId)).isEmpty();
    }

    private Message openingMessage(String content) {
        Message message = Message.builder()
                .session(session)
                .user(user)
                .role(MessageRole.ASSISTANT)
                .messageKind(MessageKind.SESSION_OPENING)
                .openingVariant("mio_01")
                .contentCiphertext(content.getBytes(StandardCharsets.UTF_8))
                .contentDekId("dek-1")
                .isCrisisFlagged(false)
                .build();
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        return message;
    }
}
