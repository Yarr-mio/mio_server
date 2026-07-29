package com.mio.ai.crisis;

import com.mio.ai.safety.SafetyL1Result;
import com.mio.session.domain.Session;
import com.mio.session.dto.SseEventDto;
import com.mio.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrisisFlowService {

    private final CrisisEventRecorder crisisEventRecorder;

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

    /**
     * 자해·자살 수단을 물었을 때의 응답 (이슈 #260).
     *
     * <p>거절과 도움 연결은 배타적이지 않다. 수단은 어떤 경우에도 안내하지 않으면서,
     * 그 질문을 하게 만든 상태에는 응답한다.
     */
    private static final String SELF_HARM_INQUIRY_RESPONSE =
            "구체적인 방법은 알려드릴 수 없어요. 그렇지만 그런 걸 찾아보게 될 만큼 지금 많이 힘드신 것 같아요. "
                    + "그 마음을 혼자 감당하지 않으셔도 돼요. 지금 바로 전문가와 이야기할 수 있는 곳을 알려드릴게요.";

    public CrisisHandleResult handle(
            SafetyL1Result l1Result,
            CrisisTrigger trigger,
            String originalMessage,
            User user,
            Session session,
            SseEmitter emitter,
            String outboundMsgId,
            Integer emotionScore) {

        int severity = determineSeverity(l1Result, trigger, originalMessage);
        String triggerType = trigger.persistedType();
        String fixedResponse = getFixedResponse(severity, trigger);

        SseEventDto.CrisisEvent crisisEvent = buildCrisisEvent(severity, fixedResponse);

        // 전송 실패해도 기록은 남겨야 하므로 여기서 흐름을 끊지 않는다. 다만 삼키지도 않는다 —
        // 위기 안내가 사용자에게 닿았는지는 이 플로우에서 가장 중요한 사실이다.
        boolean delivered = true;
        try {
            emitter.send(SseEmitter.event()
                    .name(crisisEvent.eventName())
                    .data(crisisEvent));

            SseEventDto.DoneEvent doneEvent = new SseEventDto.DoneEvent(
                    outboundMsgId, emotionScore, true, false, "crisis_flow");
            emitter.send(SseEmitter.event()
                    .name(doneEvent.eventName())
                    .data(doneEvent));
        } catch (Exception e) {
            // IOException 만 잡으면 안 된다. emitter 가 이미 완료된 상태에서 send 하면
            // IllegalStateException 이 나고, 그게 밖으로 나가면 아래 crisis_events 기록과
            // 프로파일 갱신이 통째로 건너뛰어진다. 사용자가 위기 상태였다는 사실은 전달 실패와
            // 무관하게 참이고 다음 세션의 보호 근거다.
            delivered = false;
            log.error("Crisis response was NOT delivered to the user: sessionId={} severity={}",
                    session.getId(), severity, e);
        }

        // 기록과 프로파일 갱신 알림은 recorder 가 맡는다. 저장에 성공한 경우에만 발행하며,
        // 끝내 실패하면 안전 래치를 올려 다음 프로파일이 보수적으로 만들어지게 한다 (이슈 P0-B).
        boolean recorded = crisisEventRecorder.record(user, session, severity, triggerType);

        return new CrisisHandleResult(fixedResponse, severity, delivered, recorded);
    }

    /**
     * 전송 전에 severity 와 고정 응답을 미리 계산한다.
     *
     * <p>오케스트레이터가 <b>결말을 저장한 뒤에</b> 전송하려면 이 값들이 전송보다 먼저 필요하다.
     * 순수 계산이라 {@link #handle} 이 다시 계산해도 같은 값이 나온다.
     */
    public CrisisPreview preview(SafetyL1Result l1Result, CrisisTrigger trigger, String originalMessage) {
        int severity = determineSeverity(l1Result, trigger, originalMessage);
        return new CrisisPreview(severity, getFixedResponse(severity, trigger));
    }

    public record CrisisPreview(int severity, String fixedResponse) {}

    private int determineSeverity(SafetyL1Result l1Result, CrisisTrigger trigger, String originalMessage) {
        // 자해·자살 수단 질의는 severity 3 으로 고정한다 (이슈 #260).
        // 계획·수단의 구체성은 임상적으로 단순 사고 표현보다 높은 위험 지표이며, 아래 키워드 스캔에
        // 의존하면 목록에 없는 표현이 severity 1 로 떨어져 핫라인이 붙지 않는다.
        if (trigger == CrisisTrigger.SELF_HARM_INQUIRY) {
            return 3;
        }

        // L1 hardCrisis 키워드가 매칭된 경우 severity 3 보장
        // (SEVERITY_3_KEYWORDS에 없는 HARD_CRISIS_KEYWORDS 항목도 포함)
        //
        // 맥락 마커로 강등된 위기(hardCrisisUnverified)도 같이 본다. 강등된 발화가 여기까지
        // 왔다는 것은 PolicyEngine이 검증 결과로 위기를 확정했거나 판정이 실패해 fail-closed로
        // 위기를 유지했다는 뜻이다. 그런데 강등 시 hardCrisis는 false이므로 이 조건이 없으면
        // 아래 SEVERITY_3_KEYWORDS 스캔으로 떨어지는데, 그 목록에는 "죽고싶어" 같은
        // HARD_CRISIS_KEYWORDS 항목 상당수가 없어 severity 1로 판정된다.
        // severity 1은 핫라인 리소스를 붙이지 않으므로(buildCrisisEvent) 위기 사용자에게
        // 상담 전화가 노출되지 않고, 잘못된 severity가 crisis_events에 남아
        // SafetyProfile의 14일 riskPrior까지 오염시킨다.
        if (l1Result.hardCrisis() || l1Result.hardCrisisUnverified()) {
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

    private String getFixedResponse(int severity, CrisisTrigger trigger) {
        if (trigger.requiresMeansRefusal()) {
            return SELF_HARM_INQUIRY_RESPONSE;
        }
        return switch (severity) {
            case 3 -> SEVERITY_3_RESPONSE;
            case 2 -> SEVERITY_2_RESPONSE;
            default -> SEVERITY_1_RESPONSE;
        };
    }

    /**
     * 위기 SSE 이벤트를 만든다. severity 2 이상이면 핫라인이 붙는다.
     *
     * <p>재시도 재생에서도 같은 이벤트를 복원해야 하므로 공개한다 — 텍스트만 재생하면 연결이
     * 끊겨 재시도하는 위기 사용자가 핫라인을 보지 못한다.
     */
    public SseEventDto.CrisisEvent buildCrisisEvent(int severity, String fixedResponse) {
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


    /**
     * @param delivered 위기 안내가 실제로 사용자에게 전송됐는지. {@code false} 면 기록은 남았지만
     *                  사용자는 핫라인을 보지 못했다 — 턴을 완료로 기록하면 안 된다.
     */
    public record CrisisHandleResult(
            String fixedResponse, int severity, boolean delivered, boolean recorded) {

        /** 기록 여부 개념 도입 이전 시그니처 — 기존 호출부 호환용. */
        public CrisisHandleResult(String fixedResponse, int severity, boolean delivered) {
            this(fixedResponse, severity, delivered, true);
        }

        /** 전송 여부 개념 도입 이전 시그니처 — 기존 호출부 호환용. */
        public CrisisHandleResult(String fixedResponse, int severity) {
            this(fixedResponse, severity, true, true);
        }
    }
}
