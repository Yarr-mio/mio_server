package com.mio.ai.crisis;

import com.mio.ai.safety.SafetyL1Result;
import com.mio.session.domain.Session;
import com.mio.session.dto.SseEventDto;
import com.mio.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
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

    private Set<?> extractData(SseEmitter.SseEventBuilder builder) {
        Object value = readField(builder, "dataToSend");
        if (value instanceof Set<?> set) {
            return set;
        }
        return Set.of();
    }

    private Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
