package com.mio.ai.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class OpenAiModerationClient {

    private static final String MODERATION_URL = "https://api.openai.com/v1/moderations";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiModerationClient(
            @Value("${openai.api-key}") String apiKey,
            HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public ModerationResult moderate(String text) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of("input", text));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MODERATION_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Moderation API returned {}, fail-open", response.statusCode());
                return ModerationResult.failOpen();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            log.warn("Moderation API error, fail-open: {}", e.getMessage());
            return ModerationResult.failOpen();
        }
    }

    private ModerationResult parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode result = root.path("results").get(0);

        boolean flagged = result.path("flagged").asBoolean(false);

        Map<String, Boolean> categories = new HashMap<>();
        result.path("categories").fields()
                .forEachRemaining(e -> categories.put(e.getKey(), e.getValue().asBoolean(false)));

        Map<String, Double> scores = new HashMap<>();
        result.path("category_scores").fields()
                .forEachRemaining(e -> scores.put(e.getKey(), e.getValue().asDouble(0.0)));

        return new ModerationResult(flagged, categories, scores);
    }
}
