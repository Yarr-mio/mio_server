package com.mio.session.service;

import com.mio.ai.input.InputNormalizer;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.safety.UserMessageSignalAnalyzer;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.MessageTurn;
import com.mio.session.domain.TurnStatus;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.MessageTurnRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 턴 영속화의 경계 조건 (이슈 #267).
 *
 * <p>리뷰에서 나온 세 가지를 고정한다 — Idempotency 범위, 리스 원자성, 완료 턴 재개 금지.
 */
@ExtendWith(MockitoExtension.class)
class MessageTurnPersistenceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private MessageTurnRepository messageTurnRepository;
    @Mock private MessageEncryptor messageEncryptor;
    @Mock private InputNormalizer inputNormalizer;
    @Mock private UserMessageSignalAnalyzer userMessageSignalAnalyzer;

    private SessionMessagePersistenceService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SessionMessagePersistenceService(
                sessionRepository, messageRepository, userRepository, messageTurnRepository,
                messageEncryptor, inputNormalizer, userMessageSignalAnalyzer);
    }

    private MessageTurn turn(TurnStatus status, UUID leaseToken) {
        MessageTurn t = MessageTurn.start(null, null, "key-1", UUID.randomUUID());
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(t, "status", status);
        ReflectionTestUtils.setField(t, "leaseToken", leaseToken);
        return t;
    }

    /**
     * Idempotency-Key 는 엔드포인트 호출 단위이고 이 엔드포인트는 세션에 속한다. 사용자 단위로
     * 잡으면 다른 세션에서 같은 키를 재사용했을 때 이전 세션의 턴을 재개하고, 생성된 응답이
     * 그 세션에 저장된다.
     */
    @Test
    @DisplayName("턴 조회는 사용자가 아니라 세션 범위로 한다")
    void turnLookupIsScopedToSession() {
        service.findTurn(sessionId, "key-1");

        verify(messageTurnRepository).findBySession_IdAndIdempotencyKey(sessionId, "key-1");
    }

    @Test
    @DisplayName("키가 없으면 조회하지 않는다")
    void noLookupWithoutKey() {
        assertThat(service.findTurn(sessionId, null)).isEmpty();

        verify(messageTurnRepository, never()).findBySession_IdAndIdempotencyKey(any(), any());
    }

    @Test
    @DisplayName("완료된 턴은 재개하지 않고 중복 요청으로 막는다")
    void completedTurnIsNotResumed() {
        when(messageTurnRepository.findBySession_IdAndIdempotencyKey(sessionId, "key-1"))
                .thenReturn(Optional.of(turn(TurnStatus.COMPLETED, UUID.randomUUID())));

        assertThatThrownBy(() -> service.openTurn(sessionId, userId, "안녕",
                new UserMessageSignal(50, null), "key-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_REQUEST));

        verify(messageTurnRepository, never()).save(any());
    }

    /**
     * 리스를 잃은 시도는 응답 메시지를 저장하기 전에 물러나야 한다. 순서가 반대면 어떤 턴도
     * 참조하지 않는 고아 메시지가 남는다.
     */
    @Test
    @DisplayName("리스를 잃었으면 응답 메시지를 저장하지 않는다")
    void staleAttemptDoesNotSaveAssistantMessage() {
        MessageTurn existing = turn(TurnStatus.GENERATING, UUID.randomUUID());
        when(messageTurnRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.completeTurn(existing.getId(), UUID.randomUUID(), "응답", false, "stop", null);

        verify(messageRepository, never()).save(any());
        verify(messageTurnRepository, never()).finishIfHeld(
                any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 메모리 비교만으로는 compare-and-set 이 아니다. 최종 전이는 리스 조건이 걸린 UPDATE 로
     * 이뤄져야, 그 사이에 재시도가 리스를 가져가도 덮어쓰지 않는다.
     */
    @Test
    @DisplayName("터미널 전이는 리스 조건이 걸린 UPDATE 로 수행한다")
    void terminalTransitionUsesConditionalUpdate() {
        UUID lease = UUID.randomUUID();
        MessageTurn held = turn(TurnStatus.GENERATING, lease);
        when(messageTurnRepository.findById(held.getId())).thenReturn(Optional.of(held));
        when(messageTurnRepository.finishIfHeld(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.completeTurn(held.getId(), lease, null, false, "stop", null);

        verify(messageTurnRepository).finishIfHeld(
                eq(held.getId()), eq(lease), eq(TurnStatus.COMPLETED), eq("stop"),
                eq(null), eq(null), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("실패 기록도 리스 조건이 걸린 UPDATE 로 수행한다")
    void failureAlsoUsesConditionalUpdate() {
        UUID turnId = UUID.randomUUID();
        UUID lease = UUID.randomUUID();
        when(messageTurnRepository.finishIfHeld(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        service.failTurn(turnId, lease, "error");

        verify(messageTurnRepository).finishIfHeld(
                eq(turnId), eq(lease), eq(TurnStatus.FAILED), eq("error"),
                eq(null), eq(null), any(OffsetDateTime.class));
    }
}
