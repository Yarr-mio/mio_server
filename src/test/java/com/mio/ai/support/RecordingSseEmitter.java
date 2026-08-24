package com.mio.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
public class RecordingSseEmitter extends SseEmitter {

    public record CapturedEvent(String name, JsonNode data) {}

    private static final Pattern SSE_FRAME =
            Pattern.compile("event:(\\S+)\\ndata:(.*)\\n\\n", Pattern.DOTALL);

    private final ObjectMapper objectMapper;
    private final List<CapturedEvent> captured = new ArrayList<>();

    public RecordingSseEmitter(ObjectMapper objectMapper) {
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

    public synchronized List<CapturedEvent> events() {
        return List.copyOf(captured);
    }

    public synchronized List<String> eventNames() {
        return captured.stream().map(CapturedEvent::name).toList();
    }

    /**
     * 위기 이벤트가 실어 보낸 상담 전화번호 (이슈 #499).
     *
     * <p>번호를 <b>필드로</b> 꺼낸다. 프레임을 문자열로 이어붙인 뒤 부분일치로 찾으면
     * 페이로드의 다른 숫자(타임스탬프·id)와 겹친다 — 세 자리 번호 {@code 109} 가 실제로
     * 그렇게 깨졌다. 필드를 보면 그 충돌이 구조적으로 불가능하다.
     */
    public synchronized List<String> crisisHotlineNumbers() {
        List<String> numbers = new ArrayList<>();
        for (CapturedEvent event : captured) {
            if (!"crisis".equals(event.name())) {
                continue;
            }
            for (JsonNode hotline : event.data().path("resources").path("hotlines")) {
                numbers.add(hotline.path("number").asText());
            }
        }
        return List.copyOf(numbers);
    }

    /**
     * 메시지 <b>본문</b>에 남는 텍스트. {@code delta} 는 이어 붙이고 {@code delta.replace} 는
     * 그 전까지를 전부 갈아끼운다 — "메시지 전체 교체" 의미다.
     *
     * <p>{@code crisis} 는 세지 않는다. 핫라인은 본문이 아니라 별도 블록이고, "핫라인 위에
     * 서버 문구가 남지 않는다" 를 판정할 때 본문이 비어야 하기 때문이다.
     *
     * <p>이건 {@link #everDeliveredText()} 와 <b>다른 질문</b>이다. 여기서는 덮어써진 delta 가
     * 사라지고, 거기서는 한 번 나갔다는 사실이 남는다. 하나로 합치면 두 질문 중 하나를 잃는다.
     *
     * <p>이벤트가 나갔는지가 아니라 <b>화면에 무엇이 남는지</b>로 판정해야 한다. 지우는
     * 이벤트가 뒤에 가거나 다른 {@code msg_id} 로 나가면 이벤트 목록만으로는 통과해 버린다.
     */
    public synchronized String messageBodyText() {
        StringBuilder rendered = new StringBuilder();
        for (CapturedEvent event : captured) {
            switch (event.name()) {
                case "delta" -> rendered.append(event.data().path("chunk").asText());
                case "delta.replace" -> {
                    rendered.setLength(0);
                    rendered.append(event.data().path("safe_response").asText());
                }
                default -> { }
            }
        }
        return rendered.toString();
    }

    /**
     * 한 번이라도 클라이언트로 나간 텍스트 전부 — 덮어써진 delta 도 <b>노출</b>로 센다.
     *
     * <p>{@link #messageBodyText()} 와 다른 질문이다. "지워졌으니 안 나간 것" 은 사실이 아니다 —
     * 스트리밍은 이미 화면에 그렸고 사용자가 읽었을 수 있다.
     */
    public synchronized String everDeliveredText() {
        StringBuilder delivered = new StringBuilder();
        for (CapturedEvent event : captured) {
            switch (event.name()) {
                case "crisis" -> delivered.append(event.data().path("fixed_response").asText());
                case "delta.replace" -> delivered.append(event.data().path("safe_response").asText());
                case "delta" -> delivered.append(event.data().path("chunk").asText());
                default -> { }
            }
        }
        return delivered.toString();
    }

    /** 위기 고정 응답 문구. 위기 이벤트가 없으면 비어 있다. */
    public synchronized Optional<String> crisisFixedResponse() {
        for (CapturedEvent event : captured) {
            if ("crisis".equals(event.name())) {
                return Optional.of(event.data().path("fixed_response").asText());
            }
        }
        return Optional.empty();
    }
}
