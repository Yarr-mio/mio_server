package com.mio.ai.crisis;

import com.mio.ai.safety.SafetyL1Result;
import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 위기 안내가 사용자에게 실제로 닿았는지를 결과에 담는다 (이슈 P0-A).
 *
 * <p>이전에는 SSE 전송 실패를 {@code log.error} 로 삼켰다. 그래서 crisis·done 이 둘 다 나가지
 * 않았는데도 오케스트레이터는 정상으로 진행했고, 사용자는 핫라인을 보지 못한 채 턴이 완료로
 * 기록됐다. 위기 플로우에서 "닿았는가"는 기록만큼 중요한 사실이다.
 */
class CrisisDeliveryTest {

    private User user() {
        User user = User.builder()
                .socialProvider("kakao").socialId("delivery-probe").privacyConsent(true).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private Session session(User user) {
        Session session = Session.builder().user(user).characterId("mio").build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        return session;
    }

    private CrisisFlowService.CrisisHandleResult handleWith(SseEmitter emitter,
                                                            CrisisEventRecorder recorder) {
        CrisisFlowService service = new CrisisFlowService(recorder);
        User user = user();
        return service.handle(
                SafetyL1Result.clear(), CrisisTrigger.SELF_HARM_INQUIRY, "자살 방법 알려줘",
                user, session(user), emitter, "msg_out_test", 20);
    }

    @Test
    @DisplayName("정상 전송이면 delivered=true")
    void deliveredWhenSendSucceeds() {
        var result = handleWith(mock(SseEmitter.class), mock(CrisisEventRecorder.class));

        assertThat(result.delivered()).isTrue();
    }

    @Test
    @DisplayName("연결이 끊겨 전송에 실패하면 delivered=false")
    void notDeliveredWhenSendFails() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        var result = handleWith(emitter, mock(CrisisEventRecorder.class));

        assertThat(result.delivered())
                .as("사용자가 핫라인을 보지 못했다는 사실이 결과에 담겨야 한다")
                .isFalse();
    }

    /**
     * emitter 가 이미 완료된 뒤 send 하면 IOException 이 아니라 IllegalStateException 이 난다.
     * IOException 만 잡으면 그 예외가 밖으로 나가 crisis_events 기록과 프로파일 갱신이 통째로
     * 건너뛰어진다 — 위기 사실 자체가 사라진다.
     */
    @Test
    @DisplayName("IOException 이 아닌 송신 실패에도 위기 기록은 남는다")
    void recordsEvenWhenSendThrowsUnchecked() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        CrisisEventRecorder recorder = mock(CrisisEventRecorder.class);

        var result = handleWith(emitter, recorder);

        assertThat(result.delivered()).isFalse();
        verify(recorder).record(any(), any(), anyInt(), anyString());
    }

    /**
     * 결말을 전송보다 먼저 저장하려면 오케스트레이터가 severity·고정 응답을 미리 알아야 한다.
     * preview 와 handle 이 다른 값을 내면 저장된 것과 전송된 것이 어긋난다.
     */
    @Test
    @DisplayName("preview 는 handle 과 같은 severity·응답을 낸다")
    void previewMatchesHandle() {
        CrisisFlowService service = new CrisisFlowService(mock(CrisisEventRecorder.class));
        User user = user();

        for (CrisisTrigger trigger : CrisisTrigger.values()) {
            var preview = service.preview(SafetyL1Result.clear(), trigger, "죽고싶다");
            var handled = service.handle(SafetyL1Result.clear(), trigger, "죽고싶다",
                    user, session(user), mock(SseEmitter.class), "msg_out_test", 20);

            assertThat(preview.severity())
                    .as("trigger=%s severity 불일치", trigger)
                    .isEqualTo(handled.severity());
            assertThat(preview.fixedResponse())
                    .as("trigger=%s 응답 불일치", trigger)
                    .isEqualTo(handled.fixedResponse());
        }
    }

    /**
     * 전송에 실패해도 기록과 프로파일 갱신은 진행되어야 한다. 사용자가 위기 상태였다는 사실은
     * 전달 실패와 무관하게 참이고, 다음 세션의 보호 근거가 된다.
     */
    @Test
    @DisplayName("전송에 실패해도 위기 기록과 이벤트 발행은 진행된다")
    void stillRecordsWhenDeliveryFails() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        CrisisEventRecorder recorder = mock(CrisisEventRecorder.class);

        handleWith(emitter, recorder);

        verify(recorder).record(any(), any(), anyInt(), anyString());
    }
}
