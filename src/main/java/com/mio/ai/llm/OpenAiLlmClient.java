package com.mio.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
@Slf4j
public class OpenAiLlmClient implements LlmClient, EmbeddingClient {

    private static final String CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings";
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String DONE_MARKER = "data: [DONE]";
    private static final String DATA_PREFIX = "data: ";

    private static final String REQUESTS_METRIC = "mio.llm.requests";
    private static final String USAGE_METRIC = "mio.llm.usage";
    private static final String TOKENS_METRIC = "mio.llm.tokens";
    private static final String COST_METRIC = "mio.llm.cost.usd";
    private static final String UNPRICED_METRIC = "mio.llm.cost.unpriced";
    private static final String RETRIES_METRIC = "mio.llm.retries";
    private static final String TRUNCATED_METRIC = "mio.llm.truncated";

    private static final String FINISH_REASON_LENGTH = "length";

    private static final String MODE_STREAM = "stream";
    private static final String MODE_COMPLETE_TEXT = "complete_text";
    private static final String MODE_COMPLETE_JSON = "complete_json";
    private static final String MODE_EMBED = "embed";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final LlmCostCalculator costCalculator;

    public OpenAiLlmClient(
            @Value("${openai.api-key}") String apiKey,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            LlmCostCalculator costCalculator) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.costCalculator = costCalculator;
    }

    private static final int MAX_RETRIES = 4;

    @Override
    public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
        long startMs = System.currentTimeMillis();
        AtomicLong ttft = new AtomicLong(0);
        AtomicReference<LlmUsage> usage = new AtomicReference<>();
        // 이 호출의 종료(outcome + usage)를 이미 기록했는지. 아래 catch 는 우리가 던진 예외와
        // 청크 핸들러가 던진 예외를 함께 받는데, 전자는 이미 기록돼 있어 표시가 없으면 이중 계상된다.
        //
        // outcome 과 usage 를 이 표시 하나로 함께 묶는다. 둘을 따로 두면 "요청은 셌는데 usage 는
        // 안 센" 경로가 생겨 mio.llm.requests 합계와 mio.llm.usage 합계가 어긋나고,
        // 그러면 계측 자체를 대사할 수 없다. 모든 종료 경로는 둘 다 정확히 한 번씩 남긴다.
        boolean terminalRecorded = false;

        int attempt = 0;
        while (true) {
            try {
                String requestBody = buildRequestBody(request);

                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(CHAT_URL))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<Stream<String>> response = httpClient.send(
                        httpRequest,
                        HttpResponse.BodyHandlers.ofLines()
                );

                if (response.statusCode() == 429) {
                    response.body().close();
                    if (attempt >= MAX_RETRIES) {
                        recordOutcome(request.model(), MODE_STREAM, "rate_limited");
                        recordUsage(MODE_STREAM, resolveUsage(usage, request.model()));
                        terminalRecorded = true;
                        throw new RuntimeException("OpenAI API error: 429 (rate limited, max retries exceeded)");
                    }
                    // 재시도로 삼켜진 스로틀링은 결과가 success 라 mio.llm.requests 에 흔적이 없다.
                    // 요청 수를 시도 수로 오염시키지 않도록 별도 지표로 센다.
                    meterRegistry.counter(RETRIES_METRIC,
                            "model", request.model(), "mode", MODE_STREAM, "reason", "rate_limited")
                            .increment();
                    long delayMs = streamRetryDelayMs(response, attempt);
                    log.warn("OpenAI rate limited (429), retrying in {}ms (attempt {}/{})",
                            delayMs, attempt + 1, MAX_RETRIES + 1);
                    Thread.sleep(delayMs);
                    attempt++;
                    // 재시도는 앞선 시도의 결과를 버린다. 리셋하지 않으면 실패한 시도의
                    // 사용량이 남아 최종 결과에 섞이거나 중복 집계된다.
                    ttft.set(0);
                    usage.set(null);
                    continue;
                }

                if (response.statusCode() != 200) {
                    response.body().close();
                    recordOutcome(request.model(), MODE_STREAM, "error");
                    recordUsage(MODE_STREAM, resolveUsage(usage, request.model()));
                    terminalRecorded = true;
                    throw new RuntimeException("OpenAI API error: " + response.statusCode());
                }

                try (Stream<String> lines = response.body()) {
                    lines.filter(line -> line.startsWith(DATA_PREFIX))
                            .takeWhile(line -> !line.equals(DONE_MARKER))
                            .forEach(line -> {
                                JsonNode chunk = readChunk(line.substring(DATA_PREFIX.length()));
                                if (chunk == null) {
                                    return;
                                }
                                // include_usage 를 켜면 마지막 청크가 choices=[] + usage 로 온다.
                                LlmUsage chunkUsage = extractUsage(chunk, request.model());
                                if (chunkUsage != null) {
                                    usage.set(chunkUsage);
                                }
                                recordIfTruncated(chunk, request.model(), MODE_STREAM);
                                String content = extractDeltaContent(chunk);
                                if (content != null && !content.isEmpty()) {
                                    if (ttft.get() == 0) {
                                        ttft.set(System.currentTimeMillis() - startMs);
                                    }
                                    chunkHandler.accept(content);
                                }
                            });
                }
                break;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordOutcome(request.model(), MODE_STREAM, "interrupted");
                recordUsage(MODE_STREAM, resolveUsage(usage, request.model()));
                throw new RuntimeException("LLM streaming request interrupted", e);
            } catch (RuntimeException e) {
                // 청크 핸들러가 던진 예외가 여기로 온다 (오케스트레이터는 SSE IOException 을
                // RuntimeException 으로 감싼다). 이 경로를 세지 않으면 과금은 됐는데 지표에는
                // 아무것도 남지 않는다 — 실패가 "아무 일 없었던 것"이 되는 그 구조다.
                if (!terminalRecorded) {
                    recordOutcome(request.model(), MODE_STREAM, "aborted");
                    recordUsage(MODE_STREAM, resolveUsage(usage, request.model()));
                }
                throw e;
            } catch (Exception e) {
                log.error("LLM streaming error: {}", e.getMessage());
                recordOutcome(request.model(), MODE_STREAM, "error");
                recordUsage(MODE_STREAM, resolveUsage(usage, request.model()));
                throw new RuntimeException("LLM streaming failed", e);
            }
        }

        recordOutcome(request.model(), MODE_STREAM, "success");
        LlmUsage resolved = resolveUsage(usage, request.model());
        recordUsage(MODE_STREAM, resolved);

        long ttftMs = ttft.get() > 0 ? ttft.get() : System.currentTimeMillis() - startMs;
        return new LlmStreamResult(ttftMs, resolved);
    }

    private long streamRetryDelayMs(HttpResponse<?> response, int attempt) {
        long fallback = (long) Math.pow(2, attempt) * 2000L;
        return response.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Long.parseLong(v) * 1000L;
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private String buildRequestBody(LlmRequest request) throws Exception {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("messages", messages);
        body.put("stream", true);
        // 이걸 켜지 않으면 스트리밍 응답에는 usage 가 아예 오지 않는다.
        body.put("stream_options", Map.of("include_usage", true));
        putMaxCompletionTokens(body, request);

        return objectMapper.writeValueAsString(body);
    }

    @Override
    public String completeText(LlmRequest request) {
        return complete(request, false);
    }

    @Override
    public String completeJson(LlmRequest request) {
        return complete(request, true);
    }

    private String complete(LlmRequest request, boolean jsonResponse) {
        String mode = jsonResponse ? MODE_COMPLETE_JSON : MODE_COMPLETE_TEXT;
        boolean terminalRecorded = false;
        try {
            String requestBody = buildNonStreamingRequestBody(request, jsonResponse);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(CHAT_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                recordOutcome(request.model(), mode, "error");
                recordUsage(mode, LlmUsage.unresolved(request.model()));
                terminalRecorded = true;
                throw new RuntimeException("OpenAI API error: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            // 비스트리밍 응답에는 usage 가 항상 실려 오는데 지금까지 읽지 않고 버렸다.
            // 호출부가 12곳이라 반환 타입은 그대로 두고 메트릭으로만 남긴다.
            LlmUsage usage = extractUsage(root, request.model());
            recordIfTruncated(root, request.model(), mode);
            recordOutcome(request.model(), mode, "success");
            recordUsage(mode, usage != null ? usage : LlmUsage.unresolved(request.model()));

            return root.path("choices").path(0).path("message").path("content").asText();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordOutcome(request.model(), mode, "interrupted");
            recordUsage(mode, LlmUsage.unresolved(request.model()));
            throw new RuntimeException("LLM complete request interrupted", e);
        } catch (RuntimeException e) {
            if (!terminalRecorded) {
                recordOutcome(request.model(), mode, "aborted");
                recordUsage(mode, LlmUsage.unresolved(request.model()));
            }
            throw e;
        } catch (Exception e) {
            log.error("LLM complete error: {}", e.getMessage());
            recordOutcome(request.model(), mode, "error");
            recordUsage(mode, LlmUsage.unresolved(request.model()));
            throw new RuntimeException("LLM complete failed", e);
        }
    }

    private String buildNonStreamingRequestBody(LlmRequest request, boolean jsonResponse) throws Exception {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("messages", messages);
        body.put("stream", false);
        if (jsonResponse) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        putMaxCompletionTokens(body, request);

        return objectMapper.writeValueAsString(body);
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embed() requires non-blank text");
        }
        boolean terminalRecorded = false;
        try {
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("model", EMBEDDING_MODEL, "input", text));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(EMBEDDINGS_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                recordOutcome(EMBEDDING_MODEL, MODE_EMBED, "error");
                recordUsage(MODE_EMBED, LlmUsage.unresolved(EMBEDDING_MODEL));
                terminalRecorded = true;
                throw new RuntimeException("OpenAI Embeddings API error: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            if (embeddingNode.isMissingNode() || !embeddingNode.isArray()) {
                recordOutcome(EMBEDDING_MODEL, MODE_EMBED, "error");
                recordUsage(MODE_EMBED, LlmUsage.unresolved(EMBEDDING_MODEL));
                terminalRecorded = true;
                throw new RuntimeException("Unexpected embeddings response structure: " + response.body());
            }
            float[] result = new float[embeddingNode.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = (float) embeddingNode.get(i).asDouble();
            }

            // 임베딩도 과금 대상이다. 빼면 mio.llm.cost.usd 가 실제 지출보다 낮게 나온다.
            LlmUsage usage = extractUsage(root, EMBEDDING_MODEL);
            recordOutcome(EMBEDDING_MODEL, MODE_EMBED, "success");
            recordUsage(MODE_EMBED, usage != null ? usage : LlmUsage.unresolved(EMBEDDING_MODEL));

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordOutcome(EMBEDDING_MODEL, MODE_EMBED, "interrupted");
            recordUsage(MODE_EMBED, LlmUsage.unresolved(EMBEDDING_MODEL));
            throw new RuntimeException("Embeddings request interrupted", e);
        } catch (RuntimeException e) {
            if (!terminalRecorded) {
                recordOutcome(EMBEDDING_MODEL, MODE_EMBED, "aborted");
                recordUsage(MODE_EMBED, LlmUsage.unresolved(EMBEDDING_MODEL));
            }
            throw e;
        } catch (Exception e) {
            log.error("Embeddings API error: {}", e.getMessage());
            recordOutcome(EMBEDDING_MODEL, MODE_EMBED, "error");
            recordUsage(MODE_EMBED, LlmUsage.unresolved(EMBEDDING_MODEL));
            throw new RuntimeException("Embeddings request failed", e);
        }
    }

    // ── usage 파싱·계측 ────────────────────────────────────────────

    /**
     * 응답(또는 스트리밍 청크)에서 usage 를 읽는다.
     *
     * <p>{@code usage} 가 없거나 null 이면 {@code null} 을 돌려준다 — 0 토큰짜리 사용량을
     * 만들어내지 않는다. 스트리밍은 마지막 청크에만 usage 가 실려 오므로 중간 청크에서는
     * 항상 {@code null} 이 나오는 게 정상이다.
     *
     * <p>모델 태그는 응답이 알려주는 실제 서빙 모델(예: {@code gpt-4o-2024-08-06})이 아니라
     * <b>요청한 모델</b>을 쓴다. 날짜가 붙은 모델 ID 는 단가표 키와 어긋나고 태그 카디널리티만
     * 늘린다.
     */
    private LlmUsage extractUsage(JsonNode root, String requestedModel) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        JsonNode prompt = usage.path("prompt_tokens");
        JsonNode completion = usage.path("completion_tokens");
        if (!prompt.isNumber()) {
            return null;
        }
        // 임베딩 응답에는 completion_tokens 가 없다. 출력 토큰이 실제로 0 인 경우다.
        return LlmUsage.of(requestedModel, prompt.asLong(),
                completion.isNumber() ? completion.asLong() : 0L);
    }

    /**
     * 출력 토큰 상한을 싣는다. 지정되지 않았으면 아무것도 보내지 않는다 (모델 기본 한도).
     *
     * <p>{@code max_tokens} 가 아니라 {@code max_completion_tokens} 를 쓴다. 실제 API 프로브에서
     * gpt-4o 는 둘 다 받지만, 최신 모델은 전자를 거부하므로 모델을 올릴 때 같이 깨지지 않는 쪽을
     * 고른다.
     */
    private void putMaxCompletionTokens(Map<String, Object> body, LlmRequest request) {
        if (request.maxCompletionTokens() != null) {
            body.put("max_completion_tokens", request.maxCompletionTokens());
        }
    }

    /**
     * 상한에 걸려 응답이 잘렸는지 센다.
     *
     * <p>이걸 세지 않으면 상한이 너무 빡빡해도 알 방법이 없다. 특히 JSON 모드에서는 절단이
     * 파싱 실패로 이어져 판정이 통째로 사라지는데, 그건 로그에 "파싱 실패"로만 보이고 원인이
     * 상한이었다는 사실은 드러나지 않는다.
     */
    private void recordIfTruncated(JsonNode root, String model, String mode) {
        String finishReason = root.path("choices").path(0).path("finish_reason").asText(null);
        if (FINISH_REASON_LENGTH.equals(finishReason)) {
            log.warn("LLM 응답이 출력 토큰 상한에 걸려 잘렸다: model={} mode={}", model, mode);
            meterRegistry.counter(TRUNCATED_METRIC, "model", model, "mode", mode).increment();
        }
    }

    /** 사용량을 못 받았으면 0 짜리 사용량이 아니라 '미상' 으로 만든다. */
    private LlmUsage resolveUsage(AtomicReference<LlmUsage> usage, String model) {
        LlmUsage parsed = usage.get();
        return parsed != null ? parsed : LlmUsage.unresolved(model);
    }

    private void recordOutcome(String model, String mode, String outcome) {
        meterRegistry.counter(REQUESTS_METRIC, "model", model, "mode", mode, "outcome", outcome)
                .increment();
    }

    private void recordUsage(String mode, LlmUsage usage) {
        String model = usage.model();
        if (!usage.resolved()) {
            // "사용량을 못 받았다" 를 별도 값으로 남긴다. 토큰 0 으로 세면 조용히 과소 계상된다.
            meterRegistry.counter(USAGE_METRIC, "model", model, "mode", mode, "outcome", "missing")
                    .increment();
            return;
        }

        meterRegistry.counter(USAGE_METRIC, "model", model, "mode", mode, "outcome", "resolved")
                .increment();
        meterRegistry.counter(TOKENS_METRIC, "model", model, "mode", mode, "type", "prompt")
                .increment(usage.promptTokens());
        meterRegistry.counter(TOKENS_METRIC, "model", model, "mode", mode, "type", "completion")
                .increment(usage.completionTokens());

        BigDecimal cost = costCalculator.costUsd(usage);
        if (cost == null) {
            meterRegistry.counter(UNPRICED_METRIC, "model", model, "mode", mode).increment();
            return;
        }
        meterRegistry.counter(COST_METRIC, "model", model, "mode", mode)
                .increment(cost.doubleValue());
    }

    private JsonNode readChunk(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractDeltaContent(JsonNode root) {
        JsonNode delta = root.path("choices").path(0).path("delta");
        JsonNode content = delta.path("content");
        if (!content.isMissingNode() && !content.isNull()) {
            return content.asText();
        }
        return null;
    }
}
