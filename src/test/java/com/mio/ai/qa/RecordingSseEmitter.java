package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 오케스트레이터가 실제로 내보내는 SSE 이벤트를 그대로 붙잡는 emitter (이슈 #455, P0-10).
 *
 * <p>프로덕션 경로는 {@link SseEmitter#send(SseEventBuilder)} 하나로 전송한다
 * ({@code ConversationOrchestrator.sendEvent}, {@code CrisisFlowService.handle}). 그 지점을
 * 그대로 가로채므로 <b>클라이언트가 보게 될 이벤트 이름과 페이로드</b>를 조립 규칙 변경까지
 * 포함해 검증할 수 있다 — DTO 를 직접 만들어 비교하면 전송 계층의 회귀를 놓친다.
 *
 * <p>페이로드는 두 형태로 온다. 오케스트레이터는 JSON 문자열로 직렬화해 보내고,
 * CrisisFlowService 는 DTO 객체를 그대로 보낸다. 둘 다 SSE 프레임 텍스트로 환원한 뒤
 * {@code event:}/{@code data:} 를 파싱해 같은 형태로 남긴다.
 */
class RecordingSseEmitter extends SseEmitter {

    record CapturedEvent(String name, JsonNode data) {}

    private static final Pattern SSE_FRAME =
            Pattern.compile("event:(\\S+)\\ndata:(.*)\\n\\n", Pattern.DOTALL);

    private final ObjectMapper objectMapper;
    private final List<CapturedEvent> captured = new ArrayList<>();

    RecordingSseEmitter(ObjectMapper objectMapper) {
        super(60_000L);
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void send(SseEventBuilder builder) {
        StringBuilder raw = new StringBuilder();
        for (DataWithMediaType part : builder.build()) {
            raw.append(asWireText(part.getData()));
        }
        Matcher matcher = SSE_FRAME.matcher(raw.toString());
        if (!matcher.matches()) {
            throw new AssertionError("SSE 프레임을 해석할 수 없다: " + raw);
        }
        captured.add(new CapturedEvent(matcher.group(1), readTree(matcher.group(2), raw)));
    }

    /** 문자열은 프레이밍이든 JSON 페이로드든 그대로 wire 텍스트다. 객체 페이로드만 직렬화한다. */
    private String asWireText(Object data) {
        if (data instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new AssertionError("SSE 페이로드를 직렬화할 수 없다: " + data, e);
        }
    }

    private JsonNode readTree(String json, StringBuilder raw) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("SSE data 가 JSON 이 아니다: " + raw, e);
        }
    }

    synchronized List<CapturedEvent> events() {
        return List.copyOf(captured);
    }

    synchronized List<String> eventNames() {
        return captured.stream().map(CapturedEvent::name).toList();
    }
}
