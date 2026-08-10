package com.mio.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class PushSender {

    private static final String APNS_HOST_PROD = "https://api.push.apple.com";
    private static final String APNS_HOST_SANDBOX = "https://api.sandbox.push.apple.com";
    private static final long JWT_TTL_SECONDS = 3000;
    private static final String APNS_TOKEN_PATTERN = "[0-9a-fA-F]{64}";
    private static final String APS_KEY = "aps";

    /**
     * 400 응답 중 <b>토큰 자체가 무효</b>임을 뜻하는 사유 (이슈 #411).
     *
     * <p>이전에는 400 이면 사유와 무관하게 토큰을 폐기했다. 그런데 400 에는 {@code TopicDisallowed},
     * {@code BadPriority}, {@code InvalidPushType} 처럼 <b>요청·설정</b> 문제인 사유가 다수 포함된다.
     * {@code apns-topic} 이 설정값({@code apnsBundleId})이라 잘못 배포되면 모든 요청이 같은 400 을
     * 받고, 스케줄러 한 사이클에 정상 사용자 토큰이 전량 무효화된다.
     *
     * <p>{@code DeviceTokenNotForTopic} 은 <b>제외</b>했다. 토큰이 다른 앱·토픽용일 수도, 우리 토픽
     * 설정이 틀렸을 수도 있어 구분이 불가능한데, 후자면 전 유저가 한꺼번에 죽는다.
     *
     * <p>알려지지 않은 사유도 폐기하지 않는다 — Apple 이 사유를 추가했을 때 조용히 토큰을 죽이는
     * 것보다 발송 실패로 남기는 편이 복구 가능하다.
     *
     * <p><b>남은 한계</b>: {@code BadDeviceToken} 은 sandbox 토큰을 production 으로 보낼 때도
     * 발생한다. {@code apns.is-production} 설정이 틀리면 이 사유로 전량 무효화될 수 있다.
     */
    private static final Set<String> TOKEN_INVALIDATING_APNS_REASONS = Set.of("BadDeviceToken");

    @Value("${apns.key-content:}")
    private String apnsKeyContent;

    @Value("${apns.key-path:}")
    private String apnsKeyPath;

    @Value("${apns.key-id:}")
    private String apnsKeyId;

    @Value("${apns.team-id:}")
    private String apnsTeamId;

    @Value("${apns.bundle-id:}")
    private String apnsBundleId;

    @Value("${apns.is-production:false}")
    private boolean apnsIsProduction;

    @Value("${fcm.credentials-json:}")
    private String fcmCredentialsJson;

    private PrivateKey apnsPrivateKey;
    private boolean apnsEnabled;
    private boolean fcmEnabled;

    private final AtomicReference<CachedJwt> cachedJwt = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @PostConstruct
    void init() {
        initApns();
        initFcm();
    }

    private void initApns() {
        if (apnsKeyId.isBlank() || apnsTeamId.isBlank() || apnsBundleId.isBlank()) {
            log.warn("APNs not configured — APNS_KEY_ID, APNS_TEAM_ID, or APNS_BUNDLE_ID is empty");
            return;
        }
        try {
            String pemContent = resolveApnsPem();
            if (pemContent == null) {
                log.warn("APNs not configured — neither APNS_KEY_CONTENT nor APNS_KEY_PATH is set");
                return;
            }
            String stripped = pemContent
                    .replaceAll("-----BEGIN PRIVATE KEY-----", "")
                    .replaceAll("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(stripped);
            KeyFactory kf = KeyFactory.getInstance("EC");
            apnsPrivateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            apnsEnabled = true;
            log.info("APNs initialized (production={})", apnsIsProduction);
        } catch (Exception e) {
            log.warn("APNs initialization failed: {}", e.getMessage());
        }
    }

    private String resolveApnsPem() throws Exception {
        if (apnsKeyContent != null && !apnsKeyContent.isBlank()) {
            return new String(Base64.getMimeDecoder().decode(apnsKeyContent.trim()), StandardCharsets.UTF_8);
        }
        if (apnsKeyPath != null && !apnsKeyPath.isBlank()) {
            return new String(Files.readAllBytes(Paths.get(apnsKeyPath)), StandardCharsets.UTF_8);
        }
        return null;
    }

    private void initFcm() {
        if (fcmCredentialsJson == null || fcmCredentialsJson.isBlank()) {
            log.warn("FCM not configured — FCM_CREDENTIALS_JSON is empty");
            return;
        }
        try {
            byte[] credBytes = Base64.getDecoder().decode(fcmCredentialsJson);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(credBytes)))
                    .build();
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
            }
            fcmEnabled = true;
            log.info("FCM initialized");
        } catch (Exception e) {
            log.warn("FCM initialization failed: {}", e.getMessage());
        }
    }

    /**
     * @param data 알림 탭 시 앱이 사용할 라우팅 정보 (이슈 #409). 비어 있으면 앱은 알림 종류를
     *             판별할 수 없어 OS 기본 동작(마지막 화면 복귀)으로 떨어진다.
     */
    public PushSendResult send(String token, String platform, String title, String body, Map<String, String> data) {
        try {
            // 복사는 반드시 try 안에서 한다 — data 에 null 값이 섞이면 Map.copyOf 가 NPE 를 던지는데,
            // 이게 밖으로 새면 아래 catch 의 FAILED 분류를 건너뛰고 호출자의 단말 순회와
            // 스케줄러 배치 전체를 중단시킨다(두 루프 모두 유저별 try 가 없다).
            Map<String, String> payloadData = data == null ? Map.of() : Map.copyOf(data);
            if ("ios".equalsIgnoreCase(platform)) {
                return sendApns(token, title, body, payloadData);
            } else if ("android".equalsIgnoreCase(platform)) {
                return sendFcm(token, title, body, payloadData);
            } else {
                log.warn("Unknown platform '{}', skipping push", platform);
                return PushSendResult.of(PushSendStatus.SKIPPED, "UNSUPPORTED_PLATFORM:" + platform);
            }
        } catch (Exception e) {
            // 여기까지 오는 예외는 게이트웨이 호출 <b>이전</b>의 로컬 준비 단계에서 터진 것이다
            // (payload 직렬화, JWT 서명, FCM 메시지 빌드). 요청이 나가지 않았으므로 확실한 미발송이며,
            // 재시도해도 중복 발송이 되지 않는다. AMBIGUOUS 로 접으면 억제 대상이 되어 결정적 버그가
            // 로그·지표에서 가려지므로 FAILED 로 남긴다. 응답을 못 받은 경우는 호출 지점에서 따로 잡는다.
            log.error("Push send failed before dispatch for platform={} token={}: {}",
                    platform, maskToken(token), e.getMessage());
            return PushSendResult.of(PushSendStatus.FAILED, "EXCEPTION:" + e.getClass().getSimpleName());
        }
    }

    private PushSendResult sendApns(String deviceToken, String title, String body, Map<String, String> data) throws Exception {
        if (!apnsEnabled) {
            log.debug("APNs disabled, skipping send");
            return PushSendResult.of(PushSendStatus.SKIPPED, "APNS_DISABLED");
        }
        if (deviceToken == null || !deviceToken.matches(APNS_TOKEN_PATTERN)) {
            log.warn("APNs token has invalid format: {}", maskToken(deviceToken));
            return PushSendResult.of(PushSendStatus.INVALID_TOKEN, "APNS_MALFORMED_TOKEN");
        }

        String host = apnsIsProduction ? APNS_HOST_PROD : APNS_HOST_SANDBOX;
        String url = host + "/3/device/" + deviceToken;
        String payload = buildApnsPayload(title, body, data);
        String jwt = getOrRefreshApnsJwt();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .header("authorization", "bearer " + jwt)
                .header("apns-topic", apnsBundleId)
                .header("apns-push-type", "alert")
                .header("content-type", "application/json")
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // 여기서만 발송 여부가 불명이다 — 요청이 APNs 에 닿았는지 알 수 없다.
            log.error("APNs request failed without response for token {}: {}", maskToken(deviceToken), e.getMessage());
            return PushSendResult.of(PushSendStatus.AMBIGUOUS, "APNS_NO_RESPONSE:" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("APNs request interrupted for token {}", maskToken(deviceToken));
            return PushSendResult.of(PushSendStatus.AMBIGUOUS, "APNS_NO_RESPONSE:InterruptedException");
        }

        if (response.statusCode() == 200) {
            return PushSendResult.sent();
        }

        // reason 은 본문이 비거나 파싱 실패하면 null 이다. 폐기 판정의 입력이므로 한 번만 파싱해
        // 아래 분기와 로그가 같은 값을 보게 한다.
        String reason = extractApnsReason(response.body());
        String failureReason = buildApnsFailureReason(response.statusCode(), reason);

        if (response.statusCode() == 410) {
            log.warn("APNs token no longer valid — discarding. reason={} token={}", reason, maskToken(deviceToken));
            return PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, failureReason);
        }
        // Set.of() 는 contains(null) 에서 NPE 를 던지므로 null 을 먼저 거른다.
        if (response.statusCode() == 400 && reason != null && TOKEN_INVALIDATING_APNS_REASONS.contains(reason)) {
            log.warn("APNs rejected device token — discarding. reason={} token={}", reason, maskToken(deviceToken));
            return PushSendResult.of(PushSendStatus.INVALID_TOKEN, failureReason);
        }

        // 여기 오는 응답은 토큰이 아니라 우리 요청·설정·게이트웨이 상태 문제다. 모든 요청이 같은
        // 사유로 실패하는 상황이므로 개별 토큰 문제로 오인되지 않게 ERROR 로 남긴다.
        // 특히 403(ExpiredProviderToken 등)은 JWT 캐시 때문에 전 발송이 한꺼번에 죽는다.
        log.error("APNs rejected for non-token reason — keeping device token. status={} reason={} token={}",
                response.statusCode(), reason, maskToken(deviceToken));
        return PushSendResult.of(PushSendStatus.FAILED, failureReason);
    }

    /** APNs 응답에서 사후 추적용 사유를 만든다. 토큰 등 민감 정보는 포함하지 않는다. */
    private String buildApnsFailureReason(int statusCode, String reason) {
        return reason == null ? "APNS_" + statusCode : "APNS_" + statusCode + ":" + reason;
    }

    private String extractApnsReason(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode reasonNode = objectMapper.readTree(responseBody).get("reason");
            return reasonNode == null || reasonNode.isNull() ? null : reasonNode.asText();
        } catch (Exception e) {
            log.debug("Failed to parse APNs response body for reason: {}", e.getMessage());
            return null;
        }
    }

    private PushSendResult sendFcm(String fcmToken, String title, String body, Map<String, String> data) throws Exception {
        if (!fcmEnabled) {
            log.debug("FCM disabled, skipping send");
            return PushSendResult.of(PushSendStatus.SKIPPED, "FCM_DISABLED");
        }

        Message message = buildFcmMessage(fcmToken, title, body, data);

        try {
            FirebaseMessaging.getInstance().send(message);
            return PushSendResult.sent();
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode errorCode = e.getMessagingErrorCode();
            String failureReason = "FCM_" + errorCode;
            if (errorCode == null) {
                // FCM 오류 코드가 없으면 전송 계층 실패다 — 도달 여부를 알 수 없다.
                log.error("FCM send failed without error code for token {}: {}", maskToken(fcmToken), e.getMessage());
                return PushSendResult.of(PushSendStatus.AMBIGUOUS, "FCM_TRANSPORT_ERROR");
            }
            if (errorCode == MessagingErrorCode.UNREGISTERED) {
                log.warn("FCM token expired: {}", maskToken(fcmToken));
                return PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, failureReason);
            }
            // UNREGISTERED 외의 오류는 개별 토큰 문제가 아니라 쿼터 소진·FCM 장애일 수 있다.
            // 대량 장애를 ERROR 알람으로 잡을 수 있도록 심각도를 낮추지 않는다.
            log.error("FCM send failed for token {}: code={}", maskToken(fcmToken), errorCode);
            return PushSendResult.of(PushSendStatus.FAILED, failureReason);
        }
    }

    private String getOrRefreshApnsJwt() {
        CachedJwt cached = cachedJwt.get();
        long now = Instant.now().getEpochSecond();

        if (cached != null && now - cached.issuedAt() < JWT_TTL_SECONDS) {
            return cached.jwt();
        }

        String jwt = Jwts.builder()
                .header().add("kid", apnsKeyId).and()
                .issuer(apnsTeamId)
                .issuedAt(new Date())
                .signWith(apnsPrivateKey, Jwts.SIG.ES256)
                .compact();

        cachedJwt.set(new CachedJwt(jwt, now));
        return jwt;
    }

    /**
     * FCM 메시지를 만든다.
     *
     * <p>{@code notification} 과 별개로 {@code data} 에 라우팅 정보를 싣는다. 안드로이드는 백그라운드에서
     * {@code onMessageReceived} 가 아니라 런처 Intent extras 로 이 값을 받는다.
     */
    Message buildFcmMessage(String fcmToken, String title, String body, Map<String, String> data) {
        return Message.builder()
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putAllData(data)
                .setToken(fcmToken)
                .build();
    }

    /**
     * APNs 페이로드를 만든다.
     *
     * <p>라우팅용 커스텀 키는 {@code aps} 안이 아니라 <b>형제 레벨(최상위)</b> 에 놓아야 알림 탭 시
     * {@code userInfo} 로 전달된다. {@code aps} 를 마지막에 넣어 커스텀 키가 이를 덮어쓰지 못하게 한다.
     */
    String buildApnsPayload(String title, String body, Map<String, String> data) {
        try {
            Map<String, Object> alert = Map.of("title", title, "body", body);
            Map<String, Object> aps = Map.of("alert", alert, "sound", "default");
            Map<String, Object> payload = new LinkedHashMap<>(data);
            payload.put(APS_KEY, aps);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build APNs payload", e);
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 8) + "...";
    }

    private record CachedJwt(String jwt, long issuedAt) {}
}
