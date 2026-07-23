package com.mio.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiLlmClient(
            @Value("${openai.api-key}") String apiKey,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    private static final int MAX_RETRIES = 4;

    @Override
    public long stream(LlmRequest request, Consumer<String> chunkHandler) {
        long startMs = System.currentTimeMillis();
        AtomicLong ttft = new AtomicLong(0);

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
                        throw new RuntimeException("OpenAI API error: 429 (rate limited, max retries exceeded)");
                    }
                    long delayMs = streamRetryDelayMs(response, attempt);
                    log.warn("OpenAI rate limited (429), retrying in {}ms (attempt {}/{})",
                            delayMs, attempt + 1, MAX_RETRIES + 1);
                    Thread.sleep(delayMs);
                    attempt++;
                    ttft.set(0);
                    continue;
                }

                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new RuntimeException("OpenAI API error: " + response.statusCode());
                }

                try (Stream<String> lines = response.body()) {
                    lines.filter(line -> line.startsWith(DATA_PREFIX))
                            .takeWhile(line -> !line.equals(DONE_MARKER))
                            .forEach(line -> {
                                String json = line.substring(DATA_PREFIX.length());
                                String content = extractDeltaContent(json);
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
                throw new RuntimeException("LLM streaming request interrupted", e);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.error("LLM streaming error: {}", e.getMessage());
                throw new RuntimeException("LLM streaming failed", e);
            }
        }

        return ttft.get() > 0 ? ttft.get() : System.currentTimeMillis() - startMs;
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
                throw new RuntimeException("OpenAI API error: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM complete request interrupted", e);
        } catch (Exception e) {
            log.error("LLM complete error: {}", e.getMessage());
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

        return objectMapper.writeValueAsString(body);
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embed() requires non-blank text");
        }
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
                throw new RuntimeException("OpenAI Embeddings API error: " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            if (embeddingNode.isMissingNode() || !embeddingNode.isArray()) {
                throw new RuntimeException("Unexpected embeddings response structure: " + response.body());
            }
            float[] result = new float[embeddingNode.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = (float) embeddingNode.get(i).asDouble();
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Embeddings request interrupted", e);
        } catch (Exception e) {
            log.error("Embeddings API error: {}", e.getMessage());
            throw new RuntimeException("Embeddings request failed", e);
        }
    }

    private String extractDeltaContent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode delta = root.path("choices").path(0).path("delta");
            JsonNode content = delta.path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                return content.asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
