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
import java.util.Map;
import java.util.function.Consumer;

/**
 * Gemini 2.0 Flash LlmClient 구현체.
 * CheckIn AI 응답 및 Reflection 내러티브 생성에 사용.
 */
@Component("geminiLlmClient")
@Slf4j
public class GeminiLlmClient implements LlmClient {

    private static final String API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";
    private static final int MAX_OUTPUT_TOKENS = 500;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeminiLlmClient(
            @Value("${gemini.api-key:}") String apiKey,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public long stream(LlmRequest request, Consumer<String> chunkHandler) {
        String text = complete(request);
        if (text != null) chunkHandler.accept(text);
        return 0;
    }

    @Override
    public String complete(LlmRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[GeminiLlmClient] GEMINI_API_KEY not configured");
            return null;
        }
        try {
            String prompt = buildPrompt(request);
            String body = objectMapper.writeValueAsString(Map.of(
                    "contents", new Object[]{
                            Map.of("parts", new Object[]{Map.of("text", prompt)})
                    },
                    "generationConfig", Map.of(
                            "temperature", 0.7,
                            "maxOutputTokens", MAX_OUTPUT_TOKENS
                    )
            ));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL + apiKey))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[GeminiLlmClient] status={} body={}", response.statusCode(), response.body());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.at("/candidates/0/content/parts/0/text").asText(null);

        } catch (Exception e) {
            log.warn("[GeminiLlmClient] call failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildPrompt(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        for (LlmRequest.Message msg : request.messages()) {
            if ("system".equals(msg.role())) {
                sb.append("[시스템 지시]\n").append(msg.content()).append("\n\n");
            } else {
                sb.append(msg.content());
            }
        }
        return sb.toString();
    }
}
