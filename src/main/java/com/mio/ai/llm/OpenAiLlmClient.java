package com.mio.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
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

@Primary
@Component
@Slf4j
public class OpenAiLlmClient implements LlmClient {

    private static final String CHAT_URL = "https://api.openai.com/v1/chat/completions";
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

    @Override
    public long stream(LlmRequest request, Consumer<String> chunkHandler) {
        long startMs = System.currentTimeMillis();
        AtomicLong ttft = new AtomicLong(0);

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

            if (response.statusCode() != 200) {
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

        } catch (Exception e) {
            log.error("LLM streaming error: {}", e.getMessage());
            throw new RuntimeException("LLM streaming failed", e);
        }

        return ttft.get() > 0 ? ttft.get() : System.currentTimeMillis() - startMs;
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
    public String complete(LlmRequest request) {
        try {
            String requestBody = buildNonStreamingRequestBody(request);

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

        } catch (Exception e) {
            log.error("LLM complete error: {}", e.getMessage());
            throw new RuntimeException("LLM complete failed", e);
        }
    }

    private String buildNonStreamingRequestBody(LlmRequest request) throws Exception {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("response_format", Map.of("type", "json_object"));

        return objectMapper.writeValueAsString(body);
    }

    private String extractDeltaContent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode delta = root.path("choices").get(0).path("delta");
            JsonNode content = delta.path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                return content.asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
