package com.mio.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 발송 실패를 "확실한 미발송"과 "발송 여부 불명"으로 가르는 경계를 고정한다.
 *
 * <p>이 구분이 중요한 이유: 불명({@code AMBIGUOUS})은 이미 나갔을 수 있어 재발송을 억제하지만,
 * 확실한 미발송({@code FAILED})은 억제하지 않고 재시도한다. 경계가 넓게 잡히면 게이트웨이에
 * 요청조차 나가지 않은 결정적 버그가 억제 대상이 되어 로그·지표에서 가려진다.
 */
@ExtendWith(MockitoExtension.class)
class PushSenderTest {

    private static final String VALID_APNS_TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Mock private HttpClient httpClient;

    private PushSender pushSender;

    @BeforeEach
    void setUp() {
        pushSender = new PushSender();
        ReflectionTestUtils.setField(pushSender, "httpClient", httpClient);
        ReflectionTestUtils.setField(pushSender, "apnsEnabled", true);
        ReflectionTestUtils.setField(pushSender, "apnsIsProduction", false);
        ReflectionTestUtils.setField(pushSender, "apnsKeyId", "TESTKEYID1");
        ReflectionTestUtils.setField(pushSender, "apnsTeamId", "TESTTEAMID");
        ReflectionTestUtils.setField(pushSender, "apnsBundleId", "com.test.app");
    }

    @Test
    @DisplayName("APNs 응답을 받지 못하면 발송 여부 불명(AMBIGUOUS)으로 분류한다")
    void send_whenApnsGivesNoResponse_returnsAmbiguous() throws Exception {
        ReflectionTestUtils.setField(pushSender, "apnsPrivateKey", generateEcPrivateKey());
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"));

        PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문");

        // 요청은 나갔는데 응답을 못 받았다 — APNs 가 이미 처리했을 수 있어 재발송하면 중복 도착이다
        assertThat(result.status()).isEqualTo(PushSendStatus.AMBIGUOUS);
        assertThat(result.isAmbiguous()).isTrue();
        assertThat(result.failureReason()).isEqualTo("APNS_NO_RESPONSE:IOException");
    }

    @Test
    @DisplayName("게이트웨이 호출 전 로컬 준비 단계에서 실패하면 확실한 미발송(FAILED)으로 남긴다")
    void send_whenLocalPreparationFails_returnsFailedWithoutDispatch() {
        // JWT 서명 키가 없으면 getOrRefreshApnsJwt() 가 httpClient.send() 이전에 터진다
        ReflectionTestUtils.setField(pushSender, "apnsPrivateKey", null);

        PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문");

        // 요청이 아예 나가지 않았으므로 불명이 아니다 — 억제되면 결정적 버그가 지표에서 가려진다
        assertThat(result.status()).isEqualTo(PushSendStatus.FAILED);
        assertThat(result.isAmbiguous()).isFalse();
        assertThat(result.failureReason()).startsWith("EXCEPTION:");
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("APNs 가 명시적으로 거절하면 사유와 함께 확실한 미발송으로 분류한다")
    void send_whenApnsRejects_returnsDefiniteFailure() throws Exception {
        ReflectionTestUtils.setField(pushSender, "apnsPrivateKey", generateEcPrivateKey());
        // 응답 목은 바깥 스터빙 전에 완성해 둔다 (중첩 스터빙은 Mockito 가 거부한다)
        HttpResponse<String> rejection = stubResponse(410, "{\"reason\":\"Unregistered\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(rejection);

        PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문");

        assertThat(result.status()).isEqualTo(PushSendStatus.TOKEN_EXPIRED);
        assertThat(result.isAmbiguous()).isFalse();
        assertThat(result.invalidatesToken()).isTrue();
        assertThat(result.failureReason()).isEqualTo("APNS_410:Unregistered");
    }

    @Test
    @DisplayName("잘못된 형식의 토큰은 요청을 보내지 않고 INVALID_TOKEN으로 분류한다")
    void send_whenTokenMalformed_doesNotDispatch() {
        PushSendResult result = pushSender.send("not-a-valid-token", "ios", "제목", "본문");

        assertThat(result.status()).isEqualTo(PushSendStatus.INVALID_TOKEN);
        assertThat(result.isAmbiguous()).isFalse();
        assertThat(result.failureReason()).isEqualTo("APNS_MALFORMED_TOKEN");
        verifyNoInteractions(httpClient);
    }

    private PrivateKey generateEcPrivateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair().getPrivate();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> stubResponse(int statusCode, String body) {
        HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }
}
