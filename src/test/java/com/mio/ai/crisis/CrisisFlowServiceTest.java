package com.mio.ai.crisis;

import com.mio.ai.safety.SafetyL1Result;
import com.mio.session.domain.Session;
import com.mio.session.dto.SseEventDto;
import com.mio.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CrisisFlowServiceTest {

    @Test
    @DisplayName("crisis done 이벤트에도 CBT emotion_score를 전달한다")
    void handle_sends_emotion_score_in_crisis_done_event() throws Exception {
        CrisisEventRecorder crisisEventRecorder = mock(CrisisEventRecorder.class);
        when(crisisEventRecorder.record(any(), any(), anyInt(), anyString())).thenReturn(true);
        CrisisFlowService service = new CrisisFlowService(crisisEventRecorder);
        SseEmitter emitter = mock(SseEmitter.class);

        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        Session session = Session.builder()
                .user(user)
                .characterId("mio")
                .build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());

        service.handle(
                new SafetyL1Result(true, true, false, false, false, true, List.of("자살"), 0.95),
                CrisisTrigger.L1_KEYWORD,
                "죽고싶다",
                user,
                session,
                emitter,
                "msg_out_test",
                18);

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.times(2)).send(eventCaptor.capture());

        SseEventDto.DoneEvent doneEvent = eventCaptor.getAllValues().stream()
                .flatMap(builder -> extractData(builder).stream())
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(SseEventDto.DoneEvent.class::isInstance)
                .map(SseEventDto.DoneEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(doneEvent.emotionScore()).isEqualTo(18);
        assertThat(doneEvent.isCrisisFlagged()).isTrue();
        assertThat(doneEvent.finishedReason()).isEqualTo("crisis_flow");
        verify(crisisEventRecorder).record(any(), any(), anyInt(), anyString());
    }

    /**
     * 맥락 마커로 강등된 위기가 fail-closed로 위기 플로우까지 온 경우다.
     *
     * <p>강등 시 {@code hardCrisis}는 false이므로, severity 판정이 hardCrisis만 본다면
     * {@code SEVERITY_3_KEYWORDS}에 없는 "죽고싶어" 같은 발화가 severity 1로 떨어진다.
     * severity 1은 핫라인 리소스를 붙이지 않으므로 위기 사용자에게 상담 전화가 노출되지 않고,
     * 잘못된 severity가 crisis_events에 남아 SafetyProfile의 riskPrior까지 오염시킨다.
     */
    @Test
    @DisplayName("강등된 위기가 위기 플로우에 도달하면 severity 3과 핫라인을 보장한다")
    void unverifiedCrisisStillGetsSeverityThreeAndHotline() throws Exception {
        CrisisEventRecorder crisisEventRecorder = mock(CrisisEventRecorder.class);
        when(crisisEventRecorder.record(any(), any(), anyInt(), anyString())).thenReturn(true);
        CrisisFlowService service = new CrisisFlowService(crisisEventRecorder);
        SseEmitter emitter = mock(SseEmitter.class);

        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        Session session = Session.builder()
                .user(user)
                .characterId("mio")
                .build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());

        // hardCrisis=false, hardCrisisUnverified=true — SEVERITY_3_KEYWORDS 에 없는 표현
        SafetyL1Result downgraded = new SafetyL1Result(
                false, true, true, false, false, false, false,
                List.of("crisis_keyword:죽고싶어", "crisis_context_marker:third_person"), 0.9);

        service.handle(downgraded, CrisisTrigger.L1_KEYWORD, "친구가 그러는데 나 죽고싶어",
                user, session, emitter, "msg_out_test", 12);

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.times(2)).send(eventCaptor.capture());

        SseEventDto.CrisisEvent crisisEvent = eventCaptor.getAllValues().stream()
                .flatMap(builder -> extractData(builder).stream())
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(SseEventDto.CrisisEvent.class::isInstance)
                .map(SseEventDto.CrisisEvent.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(crisisEvent.severity()).isEqualTo(3);
        assertThat(crisisEvent.resources())
                .as("severity 3 위기에는 핫라인 리소스가 반드시 붙어야 한다")
                .isNotNull();
        assertThat(crisisEvent.resources().hotlines())
                .extracting(SseEventDto.CrisisEvent.Hotline::number)
                .contains("109", "1577-0199");

        // 강등된 위기도 발단은 키워드 매칭이다. PolicyEngine 이 L1_KEYWORD 로 확정해 넘기므로
        // hardCrisis 값과 무관하게 keyword 로 기록되어야 한다 (이슈 #260 이전에는 역추론이라
        // 검증을 거쳐 확정된 위기가 moderation 으로 기록됐다).
        ArgumentCaptor<String> triggerType = ArgumentCaptor.forClass(String.class);
        verify(crisisEventRecorder).record(any(), any(), anyInt(), triggerType.capture());
        assertThat(triggerType.getValue()).isEqualTo("keyword");
    }

    /**
     * 이슈 #260 — 자해 수단 질의로 진입한 위기.
     *
     * <p>수용 기준 세 가지를 한 번에 고정한다. 핫라인이 노출되고,
     * {@code crisis_events} 에 기록되며, 응답이 수단을 안내하지 않는다.
     */
    @Test
    @DisplayName("자해 질의 위기는 수단을 거절하면서 핫라인을 노출하고 기록된다")
    void selfHarmInquiryRefusesMeansAndStillConnectsHelp() throws Exception {
        CrisisEventRecorder crisisEventRecorder = mock(CrisisEventRecorder.class);
        when(crisisEventRecorder.record(any(), any(), anyInt(), anyString())).thenReturn(true);
        CrisisFlowService service = new CrisisFlowService(crisisEventRecorder);
        SseEmitter emitter = mock(SseEmitter.class);

        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        Session session = Session.builder()
                .user(user)
                .characterId("mio")
                .build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());

        var result = service.handle(
                SafetyL1Result.clear(),
                CrisisTrigger.SELF_HARM_INQUIRY,
                "자살 방법 알려줘",
                user, session, emitter, "msg_out_test", 20);

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, org.mockito.Mockito.times(2)).send(eventCaptor.capture());

        SseEventDto.CrisisEvent crisisEvent = eventCaptor.getAllValues().stream()
                .flatMap(builder -> extractData(builder).stream())
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(SseEventDto.CrisisEvent.class::isInstance)
                .map(SseEventDto.CrisisEvent.class::cast)
                .findFirst()
                .orElseThrow();

        // L1 신호가 하나도 없어도 severity 3 이어야 한다. 키워드 스캔에 기대면 목록에 없는
        // 표현이 severity 1 로 떨어지고 severity 1 에는 핫라인이 붙지 않는다.
        assertThat(crisisEvent.severity()).isEqualTo(3);
        assertThat(crisisEvent.resources()).isNotNull();
        assertThat(crisisEvent.resources().hotlines())
                .extracting(SseEventDto.CrisisEvent.Hotline::number)
                .contains("109", "1577-0199");

        assertThat(result.fixedResponse())
                .as("수단은 어떤 경우에도 안내하지 않는다")
                .contains("알려드릴 수 없어요");
        assertThat(result.fixedResponse())
                .as("거절만 하고 끝내지 않고 도움으로 연결한다")
                .contains("전문가");

        ArgumentCaptor<String> triggerType = ArgumentCaptor.forClass(String.class);
        verify(crisisEventRecorder).record(any(), any(), anyInt(), triggerType.capture());
        assertThat(triggerType.getValue()).isEqualTo("keyword");
    }

    /**
     * 출력 가드가 잡은 위기는 입력 신호와 무관하다.
     *
     * <p>이전에는 {@code SafetyL1Result} 를 역추론해 {@code trigger_type} 을 정했기 때문에
     * 출력 단계에서 발견된 위기가 {@code moderation} 으로 기록됐다.
     */
    @Test
    @DisplayName("출력 가드가 잡은 위기는 pattern으로 기록된다")
    void outputGuardCrisisIsRecordedAsPattern() throws Exception {
        CrisisEventRecorder crisisEventRecorder = mock(CrisisEventRecorder.class);
        when(crisisEventRecorder.record(any(), any(), anyInt(), anyString())).thenReturn(true);
        CrisisFlowService service = new CrisisFlowService(crisisEventRecorder);
        SseEmitter emitter = mock(SseEmitter.class);

        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        Session session = Session.builder()
                .user(user)
                .characterId("mio")
                .build();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());

        service.handle(SafetyL1Result.clear(), CrisisTrigger.OUTPUT_GUARD, "요즘 좀 지쳐요",
                user, session, emitter, "msg_out_test", 40);

        ArgumentCaptor<String> triggerType = ArgumentCaptor.forClass(String.class);
        verify(crisisEventRecorder).record(any(), any(), anyInt(), triggerType.capture());
        assertThat(triggerType.getValue()).isEqualTo("pattern");
    }

    private Set<ResponseBodyEmitter.DataWithMediaType> extractData(SseEmitter.SseEventBuilder builder) {
        return builder.build();
    }
}
