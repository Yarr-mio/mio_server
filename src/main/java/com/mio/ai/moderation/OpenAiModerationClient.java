package com.mio.ai.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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

    /**
     * L0 판정 시도 횟수. {@code outcome} 태그로 성공/실패를 가른다 (이슈 #263).
     *
     * <p>실패율이 임계치를 넘으면 안전 계층 하나가 빠진 채로 서비스가 도는 상태이므로 알람이 필요하다.
     * 임계치와 채널은 운영 구성의 몫이라 여기서는 지표만 노출한다. 레지스트리가 붙기 전까지는
     * {@code ai_policy_decisions} 트레이스의 {@code l0_resolved} 로 같은 비율을 집계할 수 있다.
     */
    private static final String MODERATION_METRIC = "mio.moderation.requests";

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public OpenAiModerationClient(
            @Value("${openai.api-key}") String apiKey,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
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
                return failOpen("http_" + response.statusCode());
            }

            ModerationResult result = parseResponse(response.body());
            meterRegistry.counter(MODERATION_METRIC, "outcome", "resolved").increment();
            return result;
        } catch (Exception e) {
            log.warn("Moderation API error, fail-open: {}", e.getMessage());
            return failOpen("exception");
        }
    }

    private ModerationResult failOpen(String reason) {
        meterRegistry.counter(MODERATION_METRIC, "outcome", "fail_open", "reason", reason).increment();
        return ModerationResult.failOpen();
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
