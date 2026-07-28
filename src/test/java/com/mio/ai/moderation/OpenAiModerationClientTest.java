package com.mio.ai.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 이슈 #263 — L0 fail-open 이 관측되지 않으면 안전 계층 이탈이 조용히 지나간다.
 *
 * <p>fail-open 자체는 유지한다. 하드 실패로 바꾸면 Moderation 장애가 곧 서비스 중단이 된다.
 * 바꾸는 것은 그 상태가 값과 지표에 남느냐다.
 */
class OpenAiModerationClientTest {

    private HttpClient httpClient;
    private MeterRegistry meterRegistry;
    private OpenAiModerationClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        meterRegistry = new SimpleMeterRegistry();
        client = new OpenAiModerationClient("sk-test", httpClient, new ObjectMapper(), meterRegistry);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    private double counter(String outcome) {
        return meterRegistry.find("mio.moderation.requests")
                .tag("outcome", outcome)
                .counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @Test
    @DisplayName("정상 응답은 resolved=true로 표시된다")
    void successfulModerationIsResolved() throws Exception {
        stubResponse(200, """
                {"results":[{"flagged":false,"categories":{"self-harm":false},
                 "category_scores":{"self-harm":0.01}}]}
                """);

        ModerationResult result = client.moderate("오늘 날씨가 좋네요");

        assertThat(result.resolved()).isTrue();
        assertThat(result.flagged()).isFalse();
        assertThat(counter("resolved")).isEqualTo(1.0);
        assertThat(counter("fail_open")).isZero();
    }

    @Test
    @DisplayName("HTTP 오류는 fail-open이되 resolved=false로 구분된다")
    void httpErrorIsFailOpenButMarkedUnresolved() throws Exception {
        stubResponse(500, "");

        ModerationResult result = client.moderate("오늘 날씨가 좋네요");

        assertThat(result.flagged())
                .as("fail-open 은 유지한다 — 판정 로직은 기존대로 flagged 만 본다")
                .isFalse();
        assertThat(result.resolved())
                .as("'위험 신호 없음'과 '판정을 못 받아옴'이 같은 값이면 안 된다")
                .isFalse();
        assertThat(counter("fail_open")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("네트워크 예외도 resolved=false로 구분된다")
    void networkFailureIsMarkedUnresolved() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection reset"));

        ModerationResult result = client.moderate("오늘 날씨가 좋네요");

        assertThat(result.resolved()).isFalse();
        assertThat(counter("fail_open")).isEqualTo(1.0);
    }

    /**
     * HTTP 200 이어도 스키마가 온전하지 않으면 판정을 받은 게 아니다.
     *
     * <p>기본값으로 메우면 {@code flagged=false} + 빈 카테고리가 되어 정상 안전 판정과
     * 구별되지 않는다. self-harm 카테고리가 통째로 빠진 응답이 "자해 신호 없음"이 되는 것이
     * 정확히 이 이슈가 막으려는 상태다.
     */
    @Test
    @DisplayName("HTTP 200이어도 스키마가 불완전하면 resolved=false로 처리한다")
    void malformed200ResponseIsNotTreatedAsResolved() throws Exception {
        stubResponse(200, """
                {"results":[{}]}
                """);

        ModerationResult result = client.moderate("죽고싶다");

        assertThat(result.resolved())
                .as("판정을 받지 못했으므로 정상 안전 판정과 같은 값이면 안 된다")
                .isFalse();
        assertThat(counter("resolved")).isZero();
        assertThat(counter("fail_open")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("results가 비어 있으면 resolved=false로 처리한다")
    void emptyResultsArrayIsNotTreatedAsResolved() throws Exception {
        stubResponse(200, """
                {"results":[]}
                """);

        assertThat(client.moderate("죽고싶다").resolved()).isFalse();
        assertThat(counter("fail_open")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("categories만 빠진 부분 응답도 resolved=false로 처리한다")
    void partialResponseMissingCategoriesIsNotResolved() throws Exception {
        stubResponse(200, """
                {"results":[{"flagged":false,"category_scores":{"self-harm":0.01}}]}
                """);

        assertThat(client.moderate("죽고싶다").resolved()).isFalse();
        assertThat(counter("fail_open")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("판정 성공한 '이상 없음'과 fail-open은 서로 다른 값이다")
    void clearAndFailOpenAreDistinct() {
        ModerationResult clear = ModerationResult.clear();
        ModerationResult failOpen = ModerationResult.failOpen();

        assertThat(clear.flagged()).isEqualTo(failOpen.flagged());
        assertThat(clear.resolved()).isTrue();
        assertThat(failOpen.resolved()).isFalse();
        assertThat(clear).isNotEqualTo(failOpen);
    }
}
