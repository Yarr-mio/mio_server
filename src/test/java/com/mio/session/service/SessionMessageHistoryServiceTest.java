package com.mio.session.service;

import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Message;
import com.mio.session.domain.MessageKind;
import com.mio.session.domain.MessageRole;
import com.mio.session.domain.Session;
import com.mio.session.dto.SessionMessagesResponse;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 세션 대화 이력 조회 (이슈 #531).
 */
@ExtendWith(MockitoExtension.class)
class SessionMessageHistoryServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageEncryptor messageEncryptor;

    private SessionMessageHistoryService historyService;
    private UUID userId;
    private User user;
    private Session session;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        historyService = new SessionMessageHistoryService(
                sessionRepository, messageRepository, messageEncryptor);

        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("test-id")
                .privacyConsent(true)
                .build();
        user.completeOnboarding("mio");
        ReflectionTestUtils.setField(user, "id", userId);

        session = Session.builder().user(user).characterId("mio").build();
        sessionId = UUID.randomUUID();
        ReflectionTestUtils.setField(session, "id", sessionId);

        lenient().when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        // 테스트에서는 암호화를 항등 함수로 둔다 — 검증 대상은 페이지네이션과 권한이다.
        lenient().when(messageEncryptor.decrypt(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("오래된 순서로 반환하고 선제 인사가 첫 항목이다")
    void getHistory_returnsOldestFirstWithOpeningAtTop() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        when(messageRepository.findHistoryFirstPage(eq(sessionId), any())).thenReturn(List.of(
                opening("안녕! 난 미오야 🐧 오늘 어떤 하루를 보냈어?", base),
                conversation(MessageRole.USER, "오늘 발표가 있었어", base.plusSeconds(30)),
                conversation(MessageRole.ASSISTANT, "발표가 마음에 남았구나", base.plusSeconds(35))));

        SessionMessagesResponse response = historyService.getHistory(userId, sessionId, null, null);

        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.messages()).hasSize(3);
        assertThat(response.messages().get(0).kind()).isEqualTo("session_opening");
        assertThat(response.messages().get(0).role()).isEqualTo("assistant");
        assertThat(response.messages().get(1).role()).isEqualTo("user");
        assertThat(response.messages().get(2).kind()).isEqualTo("conversation");
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("limit 보다 많으면 has_next 와 next_cursor 를 준다")
    void getHistory_moreThanLimit_returnsCursor() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(conversation(MessageRole.USER, "메시지 " + i, base.plusSeconds(i)));
        }
        when(messageRepository.findHistoryFirstPage(eq(sessionId), any())).thenReturn(rows);

        SessionMessagesResponse response = historyService.getHistory(userId, sessionId, null, 2);

        assertThat(response.messages()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
    }

    @Test
    @DisplayName("다음 페이지 조회는 limit+1 건을 요청한다 — 별도 count 쿼리 없이 has_next 판정")
    void getHistory_requestsOneExtraRow() {
        when(messageRepository.findHistoryFirstPage(eq(sessionId), any())).thenReturn(List.of());

        historyService.getHistory(userId, sessionId, null, 10);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(messageRepository).findHistoryFirstPage(eq(sessionId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(11);
    }

    @Test
    @DisplayName("발급한 커서로 이어 받으면 그 지점 이후를 조회한다")
    void getHistory_withCursor_continuesAfterLastRow() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        Message first = conversation(MessageRole.USER, "첫 번째", base);
        Message second = conversation(MessageRole.ASSISTANT, "두 번째", base.plusSeconds(5));
        when(messageRepository.findHistoryFirstPage(eq(sessionId), any()))
                .thenReturn(List.of(first, second));

        String cursor = historyService.getHistory(userId, sessionId, null, 1).nextCursor();

        when(messageRepository.findHistoryAfter(eq(sessionId), any(), any(), any()))
                .thenReturn(List.of(second));
        SessionMessagesResponse page2 = historyService.getHistory(userId, sessionId, cursor, 1);

        verify(messageRepository).findHistoryAfter(
                eq(sessionId), eq(first.getCreatedAt()), eq(first.getId()), any());
        assertThat(page2.messages()).hasSize(1);
        assertThat(page2.messages().get(0).messageId()).isEqualTo(second.getId());
        assertThat(page2.hasNext()).isFalse();
    }

    @Test
    @DisplayName("복호화에 실패한 메시지는 건너뛰고 나머지를 돌려준다")
    void getHistory_undecryptableMessage_isSkipped() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        Message broken = conversation(MessageRole.USER, "깨진 메시지", base);
        Message healthy = conversation(MessageRole.ASSISTANT, "정상 메시지", base.plusSeconds(5));
        when(messageRepository.findHistoryFirstPage(eq(sessionId), any()))
                .thenReturn(List.of(broken, healthy));
        when(messageEncryptor.decrypt(broken.getContentCiphertext()))
                .thenThrow(new IllegalStateException("bad dek"));
        when(messageEncryptor.decrypt(healthy.getContentCiphertext()))
                .thenReturn("정상 메시지".getBytes(StandardCharsets.UTF_8));

        SessionMessagesResponse response = historyService.getHistory(userId, sessionId, null, null);

        assertThat(response.messages()).hasSize(1);
        assertThat(response.messages().get(0).content()).isEqualTo("정상 메시지");
    }

    @Test
    @DisplayName("복호화 실패 행도 커서 기준에 포함된다 — 같은 페이지를 무한 반복하지 않게")
    void getHistory_cursorAdvancesPastUndecryptableRow() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        Message healthy = conversation(MessageRole.USER, "정상", base);
        Message broken = conversation(MessageRole.ASSISTANT, "깨짐", base.plusSeconds(5));
        Message beyond = conversation(MessageRole.USER, "다음 페이지", base.plusSeconds(10));
        when(messageRepository.findHistoryFirstPage(eq(sessionId), any()))
                .thenReturn(List.of(healthy, broken, beyond));
        when(messageEncryptor.decrypt(broken.getContentCiphertext()))
                .thenThrow(new IllegalStateException("bad dek"));

        SessionMessagesResponse response = historyService.getHistory(userId, sessionId, null, 2);

        assertThat(response.messages()).hasSize(1);
        assertThat(response.hasNext()).isTrue();

        when(messageRepository.findHistoryAfter(eq(sessionId), any(), any(), any()))
                .thenReturn(List.of(beyond));
        historyService.getHistory(userId, sessionId, response.nextCursor(), 2);

        // 커서는 깨진 행(마지막으로 읽은 행) 기준이어야 한다.
        verify(messageRepository).findHistoryAfter(
                eq(sessionId), eq(broken.getCreatedAt()), eq(broken.getId()), any());
    }

    @Test
    @DisplayName("메시지가 없으면 빈 배열을 준다")
    void getHistory_emptySession_returnsEmptyList() {
        when(messageRepository.findHistoryFirstPage(eq(sessionId), any())).thenReturn(List.of());

        SessionMessagesResponse response = historyService.getHistory(userId, sessionId, null, null);

        assertThat(response.messages()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("남의 세션은 FORBIDDEN — 대화 원문을 반환하는 경로의 유일한 방어선")
    void getHistory_otherUsersSession_throwsForbidden() {
        assertThatThrownBy(() -> historyService.getHistory(UUID.randomUUID(), sessionId, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(messageRepository, never()).findHistoryFirstPage(any(), any());
    }

    @Test
    @DisplayName("없는 세션은 SESSION_NOT_FOUND")
    void getHistory_unknownSession_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(sessionRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> historyService.getHistory(userId, unknown, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("limit 범위를 벗어나면 INVALID_INPUT")
    void getHistory_limitOutOfRange_throws() {
        for (int invalid : new int[]{0, -1, 101}) {
            assertThatThrownBy(() -> historyService.getHistory(userId, sessionId, null, invalid))
                    .as("limit=%d", invalid)
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("깨진 커서는 INVALID_INPUT — 조용히 첫 페이지로 되돌리지 않는다")
    void getHistory_malformedCursor_throws() {
        for (String invalid : List.of("!!!not-base64!!!", "bm8tZGVsaW1pdGVy")) {
            assertThatThrownBy(() -> historyService.getHistory(userId, sessionId, invalid, null))
                    .as("cursor=%s", invalid)
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    private Message opening(String content, OffsetDateTime createdAt) {
        return message(MessageRole.ASSISTANT, MessageKind.SESSION_OPENING, "mio_intro_01", content, createdAt);
    }

    private Message conversation(MessageRole role, String content, OffsetDateTime createdAt) {
        return message(role, MessageKind.CONVERSATION, null, content, createdAt);
    }

    private Message message(MessageRole role, MessageKind kind, String variant,
                            String content, OffsetDateTime createdAt) {
        Message message = Message.builder()
                .session(session)
                .user(user)
                .role(role)
                .messageKind(kind)
                .openingVariant(variant)
                .contentCiphertext(content.getBytes(StandardCharsets.UTF_8))
                .contentDekId("dek-1")
                .isCrisisFlagged(false)
                .build();
        ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(message, "createdAt", createdAt);
        return message;
    }
}
