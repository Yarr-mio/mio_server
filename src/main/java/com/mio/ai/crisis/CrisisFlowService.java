package com.mio.ai.crisis;

import com.mio.ai.safety.SafetyL1Result;
import com.mio.crisis.domain.CrisisEvent;
import com.mio.session.domain.Session;
import com.mio.session.dto.SseEventDto;
import com.mio.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrisisFlowService {

    private final CrisisEventRepository crisisEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final List<String> SEVERITY_3_KEYWORDS = List.of(
            "자살", "자해", "죽고싶다", "죽을거야", "목숨을끊", "자살하고싶", "자해하고싶"
    );

    private static final List<String> SEVERITY_2_KEYWORDS = List.of(
            "사라지고싶다", "없어지고싶다", "살기싫다", "살고싶지않다", "삶이의미없다"
    );

    private static final String SEVERITY_1_RESPONSE =
            "지금 많이 힘드신 것 같아요. 그 마음이 정말 무겁게 느껴지시겠어요. 잠깐 숨을 고르고, 지금 이 순간에 집중해보실 수 있을까요?";

    private static final String SEVERITY_2_RESPONSE =
            "지금 이 마음이 정말 힘드시겠어요. 혼자 감당하지 않으셔도 돼요. 전문적인 도움을 받으실 수 있는 곳을 안내해드릴게요.";

    private static final String SEVERITY_3_RESPONSE =
            "지금 이 마음이 정말 많이 무거우신 것 같아요. 당신의 안전이 가장 중요해요. 지금 바로 전문가와 이야기할 수 있는 곳을 알려드릴게요.";

    public CrisisHandleResult handle(
            SafetyL1Result l1Result,
            String originalMessage,
            User user,
            Session session,
            SseEmitter emitter,
            String outboundMsgId,
            Integer emotionScore) {

        int severity = determineSeverity(l1Result, originalMessage);
        String triggerType = l1Result.hardCrisis() ? "keyword" : "moderation";
        String fixedResponse = getFixedResponse(severity);

        SseEventDto.CrisisEvent crisisEvent = buildCrisisEvent(severity, fixedResponse);

        try {
            emitter.send(SseEmitter.event()
                    .name(crisisEvent.eventName())
                    .data(crisisEvent));

            SseEventDto.DoneEvent doneEvent = new SseEventDto.DoneEvent(
                    outboundMsgId, emotionScore, true, false, "crisis_flow");
            emitter.send(SseEmitter.event()
                    .name(doneEvent.eventName())
                    .data(doneEvent));
        } catch (IOException e) {
            log.error("Failed to send crisis SSE event", e);
        }

        persistCrisisEvent(user, session, severity, triggerType);
        // SafetyProfile 즉시 invalidate (§17.8)
        eventPublisher.publishEvent(new CrisisDetectedEvent(session.getId(), user.getId(), severity));

        return new CrisisHandleResult(fixedResponse, severity);
    }

    private int determineSeverity(SafetyL1Result l1Result, String originalMessage) {
        // L1 hardCrisis 키워드가 매칭된 경우 severity 3 보장
        // (SEVERITY_3_KEYWORDS에 없는 HARD_CRISIS_KEYWORDS 항목도 포함)
        if (l1Result.hardCrisis()) {
            return 3;
        }
        if (originalMessage == null) {
            return 1;
        }
        String normalized = originalMessage.replaceAll("\\s+", "").toLowerCase();

        for (String keyword : SEVERITY_3_KEYWORDS) {
            if (normalized.contains(keyword)) return 3;
        }
        for (String keyword : SEVERITY_2_KEYWORDS) {
            if (normalized.contains(keyword)) return 2;
        }
        return 1;
    }

    private String getFixedResponse(int severity) {
        return switch (severity) {
            case 3 -> SEVERITY_3_RESPONSE;
            case 2 -> SEVERITY_2_RESPONSE;
            default -> SEVERITY_1_RESPONSE;
        };
    }

    private SseEventDto.CrisisEvent buildCrisisEvent(int severity, String fixedResponse) {
        if (severity >= 2) {
            return new SseEventDto.CrisisEvent(
                    severity,
                    fixedResponse,
                    new SseEventDto.CrisisEvent.Resources(List.of(
                            new SseEventDto.CrisisEvent.Hotline("자살예방상담전화", "109", "24/7"),
                            new SseEventDto.CrisisEvent.Hotline("정신건강위기상담전화", "1577-0199", "24/7")
                    ))
            );
        }
        return new SseEventDto.CrisisEvent(severity, fixedResponse, null);
    }

    private void persistCrisisEvent(User user, Session session, int severity, String triggerType) {
        try {
            CrisisEvent event = CrisisEvent.builder()
                    .user(user)
                    .session(session)
                    .triggerType(triggerType)
                    .severity(severity)
                    .operatorReviewed(false)
                    .build();
            crisisEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to persist crisis event", e);
        }
    }

    public record CrisisHandleResult(String fixedResponse, int severity) {}
}
