package com.mio.ai.crisis;

import com.mio.ai.safety.SafetyL1Result;
import com.mio.crisis.domain.CrisisEvent;
import com.mio.session.domain.Session;
import com.mio.session.dto.SseEventDto;
import com.mio.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CrisisFlowServiceTest {

    @Test
    @DisplayName("crisis done 이벤트에도 CBT emotion_score를 전달한다")
    void handle_sends_emotion_score_in_crisis_done_event() throws Exception {
        CrisisEventRepository crisisEventRepository = mock(CrisisEventRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        CrisisFlowService service = new CrisisFlowService(crisisEventRepository, eventPublisher);
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
        verify(crisisEventRepository).save(any());
        verify(eventPublisher).publishEvent(any(CrisisDetectedEvent.class));
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
        CrisisEventRepository crisisEventRepository = mock(CrisisEventRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        CrisisFlowService service = new CrisisFlowService(crisisEventRepository, eventPublisher);
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

        service.handle(downgraded, "친구가 그러는데 나 죽고싶어", user, session, emitter, "msg_out_test", 12);

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

        // 강등된 위기도 발단은 키워드 매칭이다. hardCrisis 만 보면 검증을 거쳐 확정된 위기가
        // 전부 moderation 으로 기록되어 crisis_events 의 발단 분석이 어긋난다.
        ArgumentCaptor<CrisisEvent> persisted = ArgumentCaptor.forClass(CrisisEvent.class);
        verify(crisisEventRepository).save(persisted.capture());
        assertThat(persisted.getValue().getTriggerType()).isEqualTo("keyword");
    }

    private Set<ResponseBodyEmitter.DataWithMediaType> extractData(SseEmitter.SseEventBuilder builder) {
        return builder.build();
    }
}
