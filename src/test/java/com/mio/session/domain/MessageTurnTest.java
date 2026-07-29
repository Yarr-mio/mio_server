package com.mio.session.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 턴 상태 전이 규칙 (docs/백엔드 문서/12_안전_신뢰성_전수조사_2026-07.md §5 P0-A).
 *
 * <p>핵심은 "터미널 상태에는 반드시 종료 사유가 있다"이다. 사유 없는 완료·실패는
 * "왜 끝났는지 모르는 턴"이 되어 이 테이블을 만든 목적을 잃는다.
 */
class MessageTurnTest {

    private MessageTurn newTurn() {
        return MessageTurn.start(null, null, "key-1", UUID.randomUUID());
    }

    @Test
    @DisplayName("턴은 generating 으로 시작하고 사용자 메시지를 참조한다")
    void startsAsGenerating() {
        UUID userMessageId = UUID.randomUUID();

        MessageTurn turn = MessageTurn.start(null, null, "key-1", userMessageId);

        assertThat(turn.getStatus()).isEqualTo(TurnStatus.GENERATING);
        assertThat(turn.getStatus().isTerminal()).isFalse();
        assertThat(turn.getUserMessageId()).isEqualTo(userMessageId);
        assertThat(turn.getFinishedReason())
                .as("진행 중인 턴에는 종료 사유가 없어야 한다")
                .isNull();
    }

    @Test
    @DisplayName("완료 시 응답 메시지와 종료 사유가 함께 남는다")
    void completeRecordsAssistantMessageAndReason() {
        MessageTurn turn = newTurn();
        UUID assistantMessageId = UUID.randomUUID();

        turn.complete(assistantMessageId, "stop");

        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(turn.getStatus().isTerminal()).isTrue();
        assertThat(turn.getAssistantMessageId()).isEqualTo(assistantMessageId);
        assertThat(turn.getFinishedReason()).isEqualTo("stop");
    }

    /**
     * 위기 플로우와 보안 거절은 고정 응답이라 assistant 메시지를 남기지 않는다.
     * 그래도 턴 자체는 완료이고, 종료 사유로 어느 경로였는지 알 수 있어야 한다.
     */
    @Test
    @DisplayName("응답 메시지 없이도 완료될 수 있다 — 고정 응답 경로")
    void completesWithoutAssistantMessage() {
        MessageTurn turn = newTurn();

        turn.complete(null, "crisis_flow");

        assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(turn.getAssistantMessageId()).isNull();
        assertThat(turn.getFinishedReason()).isEqualTo("crisis_flow");
    }

    @Test
    @DisplayName("실패 시 사유가 남고 응답 참조는 비어 있다")
    void failRecordsReason() {
        MessageTurn turn = newTurn();

        turn.fail("error");

        assertThat(turn.getStatus()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.getStatus().isTerminal()).isTrue();
        assertThat(turn.getAssistantMessageId()).isNull();
        assertThat(turn.getFinishedReason()).isEqualTo("error");
    }

    @Test
    @DisplayName("종료 사유 없이 터미널 상태로 만들 수 없다")
    void terminalRequiresReason() {
        assertThatThrownBy(() -> newTurn().complete(UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newTurn().complete(UUID.randomUUID(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newTurn().fail(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 완료된 턴을 재개하면 저장된 응답 참조가 끊겨 그 메시지가 고아가 되고, 파이프라인이 다시
     * 돌아 같은 발화에 두 번째 응답과 두 번째 LLM 호출이 생긴다. 완료된 턴은 재생 대상이다.
     */
    @Test
    @DisplayName("완료된 턴은 재개할 수 없다")
    void completedTurnCannotBeResumed() {
        MessageTurn turn = newTurn();
        turn.complete(UUID.randomUUID(), "stop");

        assertThat(turn.isResumable()).isFalse();
        assertThatThrownBy(turn::resume)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("실패·진행 중인 턴은 재개할 수 있고 이전 흔적이 지워진다")
    void failedTurnResumesCleanly() {
        MessageTurn turn = newTurn();
        turn.complete(UUID.randomUUID(), "crisis_flow", 3);
        // 완료는 재개 불가이므로 실패 턴으로 다시 만든다
        MessageTurn failed = newTurn();
        failed.fail("error");

        assertThat(failed.isResumable()).isTrue();
        failed.resume();

        assertThat(failed.getStatus()).isEqualTo(TurnStatus.GENERATING);
        assertThat(failed.getFinishedReason()).isNull();
        assertThat(failed.getAssistantMessageId()).isNull();
        assertThat(failed.getCrisisSeverity()).isNull();
    }

    /**
     * 위기로 끝난 턴은 재생 시 핫라인을 포함한 crisis 이벤트를 복원해야 한다. severity 가
     * 없으면 텍스트만 재생되어, 연결이 끊겨 재시도한 위기 사용자가 상담 번호를 보지 못한다.
     */
    @Test
    @DisplayName("위기로 끝난 턴은 severity 를 보존한다")
    void crisisTurnRetainsSeverity() {
        MessageTurn turn = newTurn();

        turn.complete(UUID.randomUUID(), "crisis_flow", 3);

        assertThat(turn.getCrisisSeverity()).isEqualTo(3);
        assertThat(turn.getFinishedReason()).isEqualTo("crisis_flow");
    }

    @Test
    @DisplayName("일반 응답으로 끝난 턴에는 severity 가 없다")
    void normalTurnHasNoSeverity() {
        MessageTurn turn = newTurn();

        turn.complete(UUID.randomUUID(), "stop");

        assertThat(turn.getCrisisSeverity()).isNull();
    }

    @Test
    @DisplayName("TurnStatus 는 DB 값과 양방향 변환된다")
    void statusRoundTrips() {
        for (TurnStatus status : TurnStatus.values()) {
            assertThat(TurnStatus.fromValue(status.value())).isEqualTo(status);
        }
        assertThatThrownBy(() -> TurnStatus.fromValue("pending"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
