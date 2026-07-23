package com.mio.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiLlmClientTest {

    @Test
    void completeText_doesNotRequestJsonResponseFormat() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = successfulResponse();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        OpenAiLlmClient client = new OpenAiLlmClient("test-key", httpClient, new ObjectMapper());

        client.completeText(LlmRequest.of("gpt-4o-mini", "system", "user"));

        String body = requestBody(capturedRequest(httpClient));
        assertThat(body).contains("\"stream\":false");
        assertThat(body).doesNotContain("response_format");
    }

    @Test
    void completeJson_requestsJsonObjectResponseFormat() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = successfulResponse();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        OpenAiLlmClient client = new OpenAiLlmClient("test-key", httpClient, new ObjectMapper());

        client.completeJson(LlmRequest.of("gpt-4o-mini", "system", "user"));

        assertThat(requestBody(capturedRequest(httpClient)))
                .contains("\"response_format\":{\"type\":\"json_object\"}");
    }

    private HttpResponse<String> successfulResponse() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}");
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
