package com.mio.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.cost.AiCostEventWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiLlmClientTest {

    private static final String MODEL = "gpt-4o-mini";

    private MeterRegistry meterRegistry;
    private LlmPricingProperties pricing;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        pricing = new LlmPricingProperties();
        pricing.setModels(Map.of(MODEL, new LlmPricingProperties.ModelPrice(
                new BigDecimal("0.15"), new BigDecimal("0.075"), new BigDecimal("0.60"))));
    }

    @Test
    void completeText_doesNotRequestJsonResponseFormat() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = successfulResponse();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).completeText(LlmRequest.of(MODEL, "system", "user"));

        String body = requestBody(capturedRequest(httpClient));
        assertThat(body).contains("\"stream\":false");
        assertThat(body).doesNotContain("response_format");
    }

    @Test
    void completeJson_requestsJsonObjectResponseFormat() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = successfulResponse();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).completeJson(LlmRequest.of(MODEL, "system", "user"));

        assertThat(requestBody(capturedRequest(httpClient)))
                .contains("\"response_format\":{\"type\":\"json_object\"}");
    }

    @Test
    @DisplayName("스트리밍 요청에 include_usage를 넣는다 — 없으면 usage가 아예 오지 않는다")
    void stream_requestsUsageInStreamOptions() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of("data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        LlmStreamResult result =
                client(httpClient).stream(LlmRequest.of(MODEL, "system", "user"), chunk -> { });

        assertThat(requestBody(capturedRequest(httpClient)))
                .contains("\"stream_options\":{\"include_usage\":true}");
        assertThat(result.ttftMs())
                .as("콘텐츠 없는 DONE-only 스트림은 종료 시간을 TTFT로 가장하면 안 된다")
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("마지막 청크의 usage를 읽어 토큰·비용을 기록한다")
    void stream_parsesUsageFromFinalChunk() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"안\"}}]}",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"녕\"}}]}",
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":40}}",
                        "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        StringBuilder received = new StringBuilder();
        LlmStreamResult result =
                client(httpClient).stream(LlmRequest.of(MODEL, "system", "user"), received::append);

        assertThat(received.toString()).isEqualTo("안녕");
        assertThat(result.usage().resolved()).isTrue();
        assertThat(result.usage().promptTokens()).isEqualTo(100);
        assertThat(result.usage().completionTokens()).isEqualTo(40);

        assertThat(counter("mio.llm.tokens", "type", "prompt")).isEqualTo(100.0);
        assertThat(counter("mio.llm.tokens", "type", "completion")).isEqualTo(40.0);
        // 0.15*100/1e6 + 0.60*40/1e6 = 0.000015 + 0.000024
        assertThat(counter("mio.llm.cost.usd", "mode", "stream")).isEqualTo(0.000039);
        assertThat(counter("mio.llm.usage", "outcome", "resolved")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("LLM 토큰과 비용을 고정된 역할 component별로 집계한다")
    void stream_recordsUsageByBoundedComponent() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":40}}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        LlmRequest request = LlmRequest.of(MODEL, "system", "user")
                .withAttribution("MAIN_GENERATION", UUID.randomUUID(), UUID.randomUUID());
        client(httpClient).stream(request, chunk -> { });

        assertThat(meterRegistry.find("mio.llm.cost.usd")
                .tag("component", "main_generation").counter().count()).isEqualTo(0.000039);
        assertThat(meterRegistry.find("mio.llm.tokens")
                .tags("component", "main_generation", "type", "prompt")
                .counter().count()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("등록되지 않은 component 문자열은 metric label로 직접 사용하지 않는다")
    void stream_mapsUnknownComponentToOther() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        LlmRequest request = LlmRequest.of(MODEL, "system", "user")
                .withAttribution("session-05619207-f2d2-43ea-bc87-a7c59ac8afa3",
                        UUID.randomUUID(), UUID.randomUUID());
        client(httpClient).stream(request, chunk -> { });

        assertThat(meterRegistry.find("mio.llm.cost.usd")
                .tag("component", "other").counter().count()).isPositive();
        assertThat(meterRegistry.getMeters())
                .flatMap(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .noneMatch(value -> value.contains("05619207"));
    }

    @Test
    @DisplayName("usage 청크가 없으면 토큰 0이 아니라 '미상'으로 남긴다")
    void stream_missingUsageIsUnresolvedNotZero() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"안녕\"}}]}",
                        "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        LlmStreamResult result =
                client(httpClient).stream(LlmRequest.of(MODEL, "system", "user"), chunk -> { });

        assertThat(result.usage().resolved())
                .as("사용량을 못 받은 것을 0 토큰으로 기록하면 비용이 조용히 과소 계상된다")
                .isFalse();
        assertThat(counter("mio.llm.usage", "outcome", "missing")).isEqualTo(1.0);
        assertThat(meterRegistry.find("mio.llm.tokens").counters()).isEmpty();
        assertThat(meterRegistry.find("mio.llm.cost.usd").counters()).isEmpty();
    }

    @Test
    @DisplayName("단가 미등록 모델은 비용 0이 아니라 unpriced로 센다")
    void stream_unpricedModelDoesNotRecordZeroCost() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}",
                        "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).stream(LlmRequest.of("gpt-unknown", "system", "user"), chunk -> { });

        assertThat(counter("mio.llm.cost.unpriced", "model", "gpt-unknown")).isEqualTo(1.0);
        assertThat(meterRegistry.find("mio.llm.cost.usd").counters())
                .as("단가를 모르는데 0원으로 세면 미등록 사실이 묻힌다")
                .isEmpty();
    }

    @Test
    @DisplayName("비스트리밍 응답의 usage도 읽는다 — 반환 타입은 그대로 두고 메트릭으로만 남긴다")
    void complete_recordsUsageFromResponse() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"answer\"}}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).completeJson(LlmRequest.of(MODEL, "system", "user"));

        assertThat(counter("mio.llm.tokens", "type", "prompt")).isEqualTo(7.0);
        assertThat(counter("mio.llm.usage", "mode", "complete_json")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("비용 이벤트 큐 거부가 비스트리밍 성공을 실패로 바꾸지 않고 드롭으로 계측된다")
    void complete_costEventRejectionIsDroppedWithoutAbortingRequest() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"answer\"}}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        AiCostEventWriter writer = rejectingCostEventWriter();

        String result = client(httpClient, writer).completeJson(
                LlmRequest.of(MODEL, "system", "user")
                        .withAttribution("OUTPUT_JUDGE", null, null));

        assertThat(result).isEqualTo("answer");
        assertThat(counter("mio.llm.requests", "outcome", "success")).isEqualTo(1.0);
        assertThat(counter("mio.llm.requests", "outcome", "aborted")).isZero();
        assertThat(counter("mio.llm.cost.events", "outcome", "dropped")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("비용 이벤트 큐 거부가 스트리밍 성공을 실패로 바꾸지 않고 드롭으로 계측된다")
    void stream_costEventRejectionIsDroppedWithoutAbortingRequest() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        AiCostEventWriter writer = rejectingCostEventWriter();

        LlmStreamResult result = client(httpClient, writer).stream(
                LlmRequest.of(MODEL, "system", "user")
                        .withAttribution("MAIN_GENERATION", null, null),
                chunk -> { });

        assertThat(result.usage().resolved()).isTrue();
        assertThat(counter("mio.llm.requests", "outcome", "success")).isEqualTo(1.0);
        assertThat(counter("mio.llm.requests", "outcome", "aborted")).isZero();
        assertThat(counter("mio.llm.cost.events", "outcome", "dropped")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("429 재시도 시 앞선 시도의 usage가 최종 결과에 섞이지 않는다")
    void stream_retryDiscardsPreviousAttemptUsage() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> throttled = mock(HttpResponse.class);
        when(throttled.statusCode()).thenReturn(429);
        when(throttled.body()).thenReturn(Stream.of(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":9999,\"completion_tokens\":9999}}"));
        when(throttled.headers()).thenReturn(
                java.net.http.HttpHeaders.of(Map.of("Retry-After", List.of("0")), (a, b) -> true));
        HttpResponse<Stream<String>> ok = streamingResponse(List.of(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(throttled, ok);

        LlmStreamResult result =
                client(httpClient).stream(LlmRequest.of(MODEL, "system", "user"), chunk -> { });

        assertThat(result.usage().promptTokens())
                .as("버려진 시도의 사용량이 남으면 비용이 부풀려 계상된다")
                .isEqualTo(11);
        assertThat(result.ttftMs())
                .as("재시도 후에도 콘텐츠가 없으면 TTFT sentinel을 유지해야 한다")
                .isEqualTo(-1);
        assertThat(counter("mio.llm.tokens", "type", "prompt")).isEqualTo(11.0);
        assertThat(counter("mio.llm.retries", "reason", "rate_limited"))
                .as("재시도로 삼켜진 스로틀링은 결과가 success 라 별도 지표가 없으면 흔적이 없다")
                .isEqualTo(1.0);
        assertThat(counter("mio.llm.requests", "outcome", "success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("청크 핸들러가 던지면 aborted로 센다 — 과금됐는데 지표에 없는 상태를 막는다")
    void stream_recordsOutcomeWhenChunkHandlerThrows() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"안\"}}]}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        // 오케스트레이터는 SSE 전송 IOException 을 RuntimeException 으로 감싸 던진다.
        assertThatThrownBy(() -> client(httpClient).stream(
                LlmRequest.of(MODEL, "system", "user"),
                chunk -> { throw new RuntimeException("SSE 전송 실패"); }))
                .isInstanceOf(RuntimeException.class);

        assertThat(counter("mio.llm.requests", "outcome", "aborted")).isEqualTo(1.0);
        assertThat(counter("mio.llm.usage", "outcome", "missing")).isEqualTo(1.0);
    }

    /**
     * 계측의 대사(reconcile) 불변식.
     *
     * <p>`mio.llm.requests` 합계와 `mio.llm.usage` 합계가 어긋나면 "요청은 셌는데 usage 는
     * 안 센" 경로가 있다는 뜻이고, 그러면 지표만 보고 누락을 알아낼 수 없다. 성공·HTTP 오류·
     * 중단 어느 경로로 끝나든 둘 다 정확히 한 번씩 올라가야 한다.
     */
    @Test
    @DisplayName("모든 종료 경로가 requests와 usage를 정확히 한 번씩 남긴다")
    void everyTerminalPathRecordsBothOutcomeAndUsage() throws Exception {
        // 1) 성공
        HttpClient ok = mock(HttpClient.class);
        HttpResponse<Stream<String>> okResponse = streamingResponse(List.of(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}",
                "data: [DONE]"));
        when(ok.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(okResponse);
        client(ok).stream(LlmRequest.of(MODEL, "s", "u"), chunk -> { });

        // 2) HTTP 오류 — 요청은 보냈지만 usage 를 못 받았다
        HttpClient failing = mock(HttpClient.class);
        HttpResponse<Stream<String>> errorResponse = mock(HttpResponse.class);
        when(errorResponse.statusCode()).thenReturn(500);
        when(errorResponse.body()).thenReturn(Stream.of());
        when(failing.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(errorResponse);
        assertThatThrownBy(() -> client(failing).stream(LlmRequest.of(MODEL, "s", "u"), chunk -> { }))
                .isInstanceOf(RuntimeException.class);

        // 3) 청크 핸들러 중단
        HttpClient aborting = mock(HttpClient.class);
        HttpResponse<Stream<String>> abortResponse = streamingResponse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}", "data: [DONE]"));
        when(aborting.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(abortResponse);
        assertThatThrownBy(() -> client(aborting).stream(LlmRequest.of(MODEL, "s", "u"),
                chunk -> { throw new RuntimeException("SSE 전송 실패"); }))
                .isInstanceOf(RuntimeException.class);

        assertThat(total("mio.llm.requests")).isEqualTo(3.0);
        assertThat(total("mio.llm.usage"))
                .as("실패 경로가 usage 를 남기지 않으면 두 합계가 어긋나 대사할 수 없다")
                .isEqualTo(3.0);
        assertThat(counter("mio.llm.usage", "outcome", "resolved")).isEqualTo(1.0);
        assertThat(counter("mio.llm.usage", "outcome", "missing"))
                .as("HTTP 오류와 중단 모두 '사용량 모름'으로 남아야 한다")
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("HTTP 오류는 aborted가 아니라 error 한 번만 센다")
    void stream_doesNotDoubleCountHttpError() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn(Stream.of());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        assertThatThrownBy(() -> client(httpClient)
                .stream(LlmRequest.of(MODEL, "system", "user"), chunk -> { }))
                .isInstanceOf(RuntimeException.class);

        assertThat(counter("mio.llm.requests", "outcome", "error")).isEqualTo(1.0);
        assertThat(counter("mio.llm.requests", "outcome", "aborted"))
                .as("이미 센 실패를 catch 에서 또 세면 실패 수가 두 배가 된다")
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("임베딩도 토큰·비용을 계측한다 — 빼면 비용 합계가 실제 지출보다 낮다")
    void embed_recordsUsageAndCost() throws Exception {
        pricing.setModels(Map.of("text-embedding-3-small",
                new LlmPricingProperties.ModelPrice(new BigDecimal("0.02"), null, BigDecimal.ZERO)));
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
                "{\"data\":[{\"embedding\":[0.1,0.2]}],\"usage\":{\"prompt_tokens\":24}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).embed("안녕하세요", "EMBEDDING", null, null);

        assertThat(counter("mio.llm.tokens", "type", "prompt")).isEqualTo(24.0);
        // 24 * 0.02 / 1e6 = 4.8e-7 — 6자리로 끊으면 0 이 되던 값이다.
        assertThat(counter("mio.llm.cost.usd", "mode", "embed")).isEqualTo(4.8e-7);
    }

    @Test
    @DisplayName("상한을 지정하면 max_completion_tokens로 보낸다 — max_tokens는 최신 모델이 거부한다")
    void sendsMaxCompletionTokensWhenSpecified() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of("data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).stream(
                LlmRequest.of(MODEL, "system", "user").withMaxCompletionTokens(400),
                chunk -> { });

        assertThat(requestBody(capturedRequest(httpClient)))
                .contains("\"max_completion_tokens\":400")
                .doesNotContain("\"max_tokens\"");
    }

    @Test
    @DisplayName("상한을 지정하지 않으면 아무것도 보내지 않는다")
    void omitsMaxCompletionTokensWhenUnspecified() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = successfulResponse();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).completeJson(LlmRequest.of(MODEL, "system", "user"));

        assertThat(requestBody(capturedRequest(httpClient)))
                .doesNotContain("max_completion_tokens");
    }

    @Test
    @DisplayName("상한에 걸려 잘리면 계측한다 — 안 세면 상한이 빡빡해도 알 수 없다")
    void recordsTruncationOnNonStreaming() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"length\","
                + "\"message\":{\"content\":\"{\\\"a\\\": \\\"잘린\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":400}}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).completeJson(
                LlmRequest.of(MODEL, "system", "user").withMaxCompletionTokens(400));

        assertThat(counter("mio.llm.truncated", "mode", "complete_json")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("스트리밍 절단도 계측한다 — 실측상 스트리밍도 finish_reason=length를 준다")
    void recordsTruncationOnStreaming() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"인지행동\"}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).stream(
                LlmRequest.of(MODEL, "system", "user").withMaxCompletionTokens(400),
                chunk -> { });

        assertThat(counter("mio.llm.truncated", "mode", "stream")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("절단 여부를 결과로 돌려준다 — 호출부가 잘린 텍스트를 정본으로 저장하지 않게")
    void streamResultExposesTruncation() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"요약 앞부분\"}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        LlmStreamResult result = client(httpClient).stream(
                LlmRequest.of(MODEL, "system", "user").withMaxCompletionTokens(400),
                chunk -> { });

        assertThat(result.truncated())
                .as("지표만 올리고 결과에 안 알려주면 잘린 요약이 그대로 저장된다")
                .isTrue();
    }

    @Test
    @DisplayName("정상 종료면 truncated=false")
    void streamResultIsNotTruncatedOnNormalStop() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"완결된 요약\"},\"finish_reason\":\"stop\"}]}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        LlmStreamResult result =
                client(httpClient).stream(LlmRequest.of(MODEL, "system", "user"), chunk -> { });

        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("정상 종료는 절단으로 세지 않는다")
    void doesNotRecordTruncationOnNormalStop() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<Stream<String>> response = streamingResponse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"안녕\"},\"finish_reason\":\"stop\"}]}",
                "data: [DONE]"));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        client(httpClient).stream(LlmRequest.of(MODEL, "system", "user"), chunk -> { });

        assertThat(meterRegistry.find("mio.llm.truncated").counters()).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────

    private OpenAiLlmClient client(HttpClient httpClient) {
        return client(httpClient, mock(AiCostEventWriter.class));
    }

    private OpenAiLlmClient client(HttpClient httpClient, AiCostEventWriter costEventWriter) {
        return new OpenAiLlmClient("test-key", httpClient, new ObjectMapper(),
                meterRegistry, new LlmCostCalculator(pricing), costEventWriter,
                ModelCatalog.defaults());
    }

    private AiCostEventWriter rejectingCostEventWriter() {
        AiCostEventWriter writer = mock(AiCostEventWriter.class);
        doThrow(new TaskRejectedException("queue full"))
                .when(writer).write(any(), any(), any(), any(), any(),
                        anyLong(), anyLong(), anyLong(), any(), any());
        return writer;
    }

    private double total(String name) {
        return meterRegistry.find(name).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private double counter(String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private HttpResponse<String> successfulResponse() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}");
        return response;
    }

    private HttpResponse<Stream<String>> streamingResponse(List<String> lines) {
        HttpResponse<Stream<String>> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(lines.stream());
        return response;
    }

    private HttpRequest capturedRequest(HttpClient httpClient) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getValue();
    }

    private String requestBody(HttpRequest request) {
        CompletableFuture<String> result = new CompletableFuture<>();
        StringBuilder body = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                body.append(StandardCharsets.UTF_8.decode(item.duplicate()));
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(body.toString());
            }
        });
        return result.join();
    }
}
