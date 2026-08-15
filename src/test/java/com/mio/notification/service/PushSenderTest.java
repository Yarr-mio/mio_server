package com.mio.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

        PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

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

        PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

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

        PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

        assertThat(result.status()).isEqualTo(PushSendStatus.TOKEN_EXPIRED);
        assertThat(result.isAmbiguous()).isFalse();
        assertThat(result.invalidatesToken()).isTrue();
        assertThat(result.failureReason()).isEqualTo("APNS_410:Unregistered");
    }

    @Test
    @DisplayName("잘못된 형식의 토큰은 요청을 보내지 않고 INVALID_TOKEN으로 분류한다")
    void send_whenTokenMalformed_doesNotDispatch() {
        PushSendResult result = pushSender.send("not-a-valid-token", "ios", "제목", "본문", Map.of());

        assertThat(result.status()).isEqualTo(PushSendStatus.INVALID_TOKEN);
        assertThat(result.isAmbiguous()).isFalse();
        assertThat(result.failureReason()).isEqualTo("APNS_MALFORMED_TOKEN");
        verifyNoInteractions(httpClient);
    }

    /**
     * 400 응답 중 <b>토큰 자체가 무효인 사유만</b> 토큰을 폐기한다 (이슈 #411).
     *
     * <p>이전에는 400 이면 사유와 무관하게 {@code INVALID_TOKEN} 이었다. 그런데 {@code apns-topic}
     * 은 설정값이라 잘못 배포되면 <b>모든</b> 요청이 400 을 받고, 스케줄러 한 사이클에 정상 사용자
     * 토큰이 전량 무효화된다. 복구는 유저가 앱을 다시 열어야 하는데 이 서비스에서는 푸시가 곧
     * 재방문 유도 장치라 복구가 느리다.
     */
    @Nested
    @DisplayName("APNs 400 사유별 토큰 폐기 판정")
    class Apns400ReasonHandling {

        @BeforeEach
        void enableApns() throws Exception {
            ReflectionTestUtils.setField(pushSender, "apnsPrivateKey", generateEcPrivateKey());
        }

        @ParameterizedTest(name = "{0} → 토큰 폐기")
        @ValueSource(strings = {"BadDeviceToken"})
        @DisplayName("토큰 자체가 무효인 사유는 토큰을 폐기한다")
        void send_tokenSpecificReason_invalidatesToken(String reason) throws Exception {
            stubApnsResponse(400, "{\"reason\":\"" + reason + "\"}");

            PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

            assertThat(result.status()).isEqualTo(PushSendStatus.INVALID_TOKEN);
            assertThat(result.invalidatesToken()).isTrue();
            assertThat(result.failureReason()).isEqualTo("APNS_400:" + reason);
        }

        /**
         * 이 사유들은 토픽·우선순위·푸시타입 등 <b>요청 설정</b> 문제다. 토큰과 무관하므로 폐기하면
         * 설정 오류 한 번에 전 유저 토큰이 죽는다.
         */
        @ParameterizedTest(name = "{0} → 토큰 유지")
        @ValueSource(strings = {
                "TopicDisallowed", "BadPriority", "InvalidPushType", "IdleTimeout",
                "MissingDeviceToken", "BadExpirationDate", "BadTopic", "MissingTopic", "PayloadEmpty"
        })
        @DisplayName("설정·요청 문제인 사유는 토큰을 폐기하지 않는다")
        void send_configurationReason_keepsToken(String reason) throws Exception {
            stubApnsResponse(400, "{\"reason\":\"" + reason + "\"}");

            PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

            assertThat(result.status()).isEqualTo(PushSendStatus.FAILED);
            assertThat(result.invalidatesToken()).isFalse();
            assertThat(result.failureReason()).isEqualTo("APNS_400:" + reason);
        }

        /**
         * 토큰이 다른 토픽·환경용일 수도, 우리 토픽 설정이 틀렸을 수도 있어 구분이 불가능하다.
         * 후자면 전 유저가 한꺼번에 죽으므로 폐기하지 않는 쪽을 택한다.
         */
        @Test
        @DisplayName("DeviceTokenNotForTopic 은 설정 오류와 구분되지 않으므로 토큰을 폐기하지 않는다")
        void send_deviceTokenNotForTopic_keepsToken() throws Exception {
            stubApnsResponse(400, "{\"reason\":\"DeviceTokenNotForTopic\"}");

            PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

            assertThat(result.status()).isEqualTo(PushSendStatus.FAILED);
            assertThat(result.invalidatesToken()).isFalse();
        }

        @Test
        @DisplayName("사유를 알 수 없는 400 은 보수적으로 토큰을 유지한다")
        void send_unknownReason_keepsToken() throws Exception {
            stubApnsResponse(400, "{\"reason\":\"SomeFutureReason\"}");

            PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

            assertThat(result.status()).isEqualTo(PushSendStatus.FAILED);
            assertThat(result.invalidatesToken()).isFalse();
        }

        @Test
        @DisplayName("본문이 비어 사유를 못 읽는 400 도 토큰을 유지한다")
        void send_emptyBody_keepsToken() throws Exception {
            stubApnsResponse(400, "");

            PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

            assertThat(result.status()).isEqualTo(PushSendStatus.FAILED);
            assertThat(result.invalidatesToken()).isFalse();
            assertThat(result.failureReason()).isEqualTo("APNS_400");
            // null 가드가 빠지면 NPE 가 send() 의 catch-all 에 잡혀 EXCEPTION:... 으로 둔갑한다.
            // 위 두 단언은 그 경로에서도 통과하므로 이 단언이 실제 검출력을 갖는다.
            assertThat(result.failureReason()).doesNotStartWith("EXCEPTION");
        }

        @Test
        @DisplayName("410 Unregistered 는 기존대로 토큰을 폐기한다")
        void send_unregistered_stillInvalidates() throws Exception {
            stubApnsResponse(410, "{\"reason\":\"Unregistered\"}");

            PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

            assertThat(result.status()).isEqualTo(PushSendStatus.TOKEN_EXPIRED);
            assertThat(result.invalidatesToken()).isTrue();
        }

        /**
         * 400·410 이 아닌 상태 코드도 토큰을 폐기해서는 안 된다.
         *
         * <p>이 경로가 고정되지 않으면 400 만 좁혀놓고 바로 옆 분기로 같은 대량 무효화가 되돌아온다.
         * 특히 403 {@code ExpiredProviderToken} / {@code InvalidProviderToken} 은 키·팀 ID 오설정이라
         * <b>모든</b> 요청이 받는다. JWT 가 캐시되므로 전 발송이 한꺼번에 죽는다.
         */
        @ParameterizedTest(name = "{0} {1} → 토큰 유지")
        @CsvSource({
                "403, InvalidProviderToken",
                "403, ExpiredProviderToken",
                "429, TooManyRequests",
                "500, InternalServerError",
                "503, ServiceUnavailable"
        })
        @DisplayName("400·410 외의 거절은 토큰을 폐기하지 않는다")
        void send_nonTokenStatusCode_keepsToken(int statusCode, String reason) throws Exception {
            stubApnsResponse(statusCode, "{\"reason\":\"" + reason + "\"}");

            PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

            assertThat(result.status()).isEqualTo(PushSendStatus.FAILED);
            assertThat(result.invalidatesToken()).isFalse();
            assertThat(result.failureReason()).isEqualTo("APNS_" + statusCode + ":" + reason);
        }

        /**
         * 응답 본문이 JSON 이 아닐 수 있다 (프록시·LB 가 HTML 오류 페이지를 반환하는 경우).
         * {@code extractApnsReason} 은 이제 폐기 판정의 입력이므로 파싱 실패도 안전해야 한다.
         */
        @Test
        @DisplayName("본문이 JSON 이 아니어도 파싱 실패로 죽지 않고 토큰을 유지한다")
        void send_nonJsonBody_keepsToken() throws Exception {
            stubApnsResponse(400, "<html><body>502 Bad Gateway</body></html>");

            PushSendResult result = pushSender.send(VALID_APNS_TOKEN, "ios", "제목", "본문", Map.of());

            assertThat(result.status()).isEqualTo(PushSendStatus.FAILED);
            assertThat(result.invalidatesToken()).isFalse();
            // 파싱 예외가 send() 의 catch-all 로 새면 EXCEPTION:... 이 된다 — 그 경로가 아님을 고정한다
            assertThat(result.failureReason()).isEqualTo("APNS_400");
        }

        private void stubApnsResponse(int statusCode, String body) throws Exception {
            HttpResponse<String> response = stubResponse(statusCode, body);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response);
        }
    }

    /**
     * 알림 탭 라우팅 data 가 APNs 페이로드의 올바른 위치에 실리는지 고정한다 (이슈 #409).
     *
     * <p>커스텀 키가 {@code aps} <b>안</b>으로 들어가면 알림 탭 시 {@code userInfo} 로 전달되지 않아,
     * 앱이 알림 종류를 판별하지 못하고 OS 기본 동작(마지막 화면 복귀)으로 떨어진다.
     */
    @Nested
    @DisplayName("APNs 페이로드 라우팅 data")
    class ApnsPayloadRouting {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("라우팅 data 는 aps 가 아니라 형제 레벨(최상위)에 실린다")
        void buildApnsPayload_putsCustomKeysAtTopLevel() throws Exception {
            Map<String, String> data = Map.of(
                    "type", "checkin_reminder_morning",
                    "route", "/checkin",
                    "slot", "morning"
            );

            JsonNode payload = objectMapper.readTree(
                    pushSender.buildApnsPayload("아침 체크인", "오늘 기분은 어때요?", data)
            );

            assertThat(payload.get("type").asText()).isEqualTo("checkin_reminder_morning");
            assertThat(payload.get("route").asText()).isEqualTo("/checkin");
            assertThat(payload.get("slot").asText()).isEqualTo("morning");
            assertThat(payload.get("aps").has("route")).isFalse();
        }

        @Test
        @DisplayName("alert 제목·본문과 sound 는 그대로 유지된다")
        void buildApnsPayload_keepsApsBlockIntact() throws Exception {
            JsonNode payload = objectMapper.readTree(
                    pushSender.buildApnsPayload("제목", "본문", Map.of("route", "/checkin"))
            );

            JsonNode aps = payload.get("aps");
            assertThat(aps.get("alert").get("title").asText()).isEqualTo("제목");
            assertThat(aps.get("alert").get("body").asText()).isEqualTo("본문");
            assertThat(aps.get("sound").asText()).isEqualTo("default");
        }

        @Test
        @DisplayName("data 가 비면 기존과 동일하게 aps 만 담는다")
        void buildApnsPayload_withEmptyData_producesApsOnly() throws Exception {
            JsonNode payload = objectMapper.readTree(
                    pushSender.buildApnsPayload("제목", "본문", Map.of())
            );

            assertThat(payload.properties()).hasSize(1);
            assertThat(payload.has("aps")).isTrue();
        }

        /**
         * 빌더가 아니라 <b>실제 전송 경로</b>를 지난다.
         *
         * <p>{@code buildApnsPayload} 를 직접 호출하는 테스트는 "올바른 JSON 을 만들 수 있다"만 보증할
         * 뿐 "올바른 JSON 을 보낸다"는 보증하지 않는다. 이 테스트가 없으면 {@code sendApns} 가
         * {@code data} 를 빈 맵으로 바꿔 넘겨도 스위트가 통과한다 — 이 PR 이 막으려는 증상 그대로다.
         */
        @Test
        @DisplayName("[배선] send() 로 넘긴 라우팅 data 가 실제 전송되는 요청 본문에 실린다")
        void send_carriesRoutingDataIntoDispatchedRequest() throws Exception {
            ReflectionTestUtils.setField(pushSender, "apnsPrivateKey", generateEcPrivateKey());
            // 200 경로는 body 를 읽지 않으므로 상태 코드만 스텁한다
            HttpResponse<String> accepted = stubStatusOnlyResponse(200);
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(accepted);

            PushSendResult result = pushSender.send(
                    VALID_APNS_TOKEN, "ios", "아침 체크인", "본문",
                    Map.of("type", "checkin_reminder_morning", "route", "/checkin", "slot", "morning"));

            assertThat(result.isSent()).isTrue();
            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));

            JsonNode dispatched = objectMapper.readTree(bodyOf(captor.getValue()));
            assertThat(dispatched.get("route").asText()).isEqualTo("/checkin");
            assertThat(dispatched.get("slot").asText()).isEqualTo("morning");
            assertThat(dispatched.get("type").asText()).isEqualTo("checkin_reminder_morning");
            assertThat(dispatched.get("aps").get("alert").get("title").asText()).isEqualTo("아침 체크인");
        }

        @Test
        @DisplayName("data 에 aps 키가 섞여 들어와도 실제 aps 블록을 덮어쓰지 못한다")
        void buildApnsPayload_dataCannotOverrideAps() throws Exception {
            Map<String, String> hostile = new LinkedHashMap<>();
            hostile.put("aps", "overwritten");
            hostile.put("route", "/checkin");

            JsonNode payload = objectMapper.readTree(
                    pushSender.buildApnsPayload("제목", "본문", hostile)
            );

            assertThat(payload.get("aps").isObject()).isTrue();
            assertThat(payload.get("aps").get("alert").get("title").asText()).isEqualTo("제목");
            assertThat(payload.get("route").asText()).isEqualTo("/checkin");
        }
    }

    /**
     * FCM 은 실제 발송에 Firebase 정적 싱글턴이 필요해 전송까지는 태울 수 없다. 대신 메시지 조립을
     * 분리해 검증한다 — {@code putAllData} 가 빠지면 안드로이드 전 기기에서 라우팅이 죽는데,
     * 이 테스트가 없으면 그 회귀가 CI 를 그대로 통과한다.
     */
    @Nested
    @DisplayName("FCM 메시지 라우팅 data")
    class FcmMessageRouting {

        /** Firebase Message 는 getter 를 노출하지 않으므로 필드 가시성을 열어 직렬화로 확인한다. */
        private final ObjectMapper fieldReadingMapper = new ObjectMapper()
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        @Test
        @DisplayName("라우팅 data 가 FCM 메시지의 data 에 실린다")
        void buildFcmMessage_attachesRoutingData() {
            Message message = pushSender.buildFcmMessage(
                    "fcm-token", "아침 체크인", "본문",
                    Map.of("type", "checkin_reminder_morning", "route", "/checkin", "slot", "morning"));

            JsonNode data = fieldReadingMapper.valueToTree(message).get("data");
            assertThat(data.get("route").asText()).isEqualTo("/checkin");
            assertThat(data.get("slot").asText()).isEqualTo("morning");
            assertThat(data.get("type").asText()).isEqualTo("checkin_reminder_morning");
        }

        @Test
        @DisplayName("notification 제목·본문은 data 와 별개로 유지된다")
        void buildFcmMessage_keepsNotificationBlock() {
            Message message = pushSender.buildFcmMessage("fcm-token", "제목", "본문", Map.of("route", "/todo"));

            JsonNode tree = fieldReadingMapper.valueToTree(message);
            assertThat(tree.get("notification").get("title").asText()).isEqualTo("제목");
            assertThat(tree.get("notification").get("body").asText()).isEqualTo("본문");
            assertThat(tree.get("data").get("route").asText()).isEqualTo("/todo");
        }
    }

    /** 전송된 요청의 본문을 문자열로 되돌린다. BodyPublisher 는 스트림이라 구독해서 읽어야 한다. */
    private String bodyOf(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        StringBuilder collected = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ByteBuffer item) {
                collected.append(StandardCharsets.UTF_8.decode(item));
            }
            @Override public void onError(Throwable throwable) {
                done.countDown();
            }
            @Override public void onComplete() {
                done.countDown();
            }
        });
        done.await(5, TimeUnit.SECONDS);
        return collected.toString();
    }

    private PrivateKey generateEcPrivateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair().getPrivate();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> stubStatusOnlyResponse(int statusCode) {
        HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        return response;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> stubResponse(int statusCode, String body) {
        HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }
}
