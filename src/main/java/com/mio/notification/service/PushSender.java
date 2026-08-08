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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class PushSender {

    private static final String APNS_HOST_PROD = "https://api.push.apple.com";
    private static final String APNS_HOST_SANDBOX = "https://api.sandbox.push.apple.com";
    private static final long JWT_TTL_SECONDS = 3000;
    private static final String APNS_TOKEN_PATTERN = "[0-9a-fA-F]{64}";

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

    public PushSendResult send(String token, String platform, String title, String body) {
        try {
            if ("ios".equalsIgnoreCase(platform)) {
                return sendApns(token, title, body);
            } else if ("android".equalsIgnoreCase(platform)) {
                return sendFcm(token, title, body);
            } else {
                log.warn("Unknown platform '{}', skipping push", platform);
                return PushSendResult.of(PushSendStatus.SKIPPED, "UNSUPPORTED_PLATFORM:" + platform);
            }
        } catch (Exception e) {
            // 요청은 나갔는데 응답을 못 받은 것일 수 있다(타임아웃 등). 게이트웨이가 이미 처리했다면
            // 재시도가 중복 발송이 되므로, 확실한 실패와 구분해 AMBIGUOUS 로 분류한다.
            log.error("Push send failed for platform={} token={}: {}", platform, maskToken(token), e.getMessage());
            return PushSendResult.of(PushSendStatus.AMBIGUOUS, "EXCEPTION:" + e.getClass().getSimpleName());
        }
    }

    private PushSendResult sendApns(String deviceToken, String title, String body) throws Exception {
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
        String payload = buildApnsPayload(title, body);
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

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return PushSendResult.sent();
        }

        log.warn("APNs rejected token {}: status={} body={}", maskToken(deviceToken), response.statusCode(), response.body());
        String failureReason = buildApnsFailureReason(response.statusCode(), response.body());
        if (response.statusCode() == 410) {
            return PushSendResult.of(PushSendStatus.TOKEN_EXPIRED, failureReason);
        }
        if (response.statusCode() == 400) {
            return PushSendResult.of(PushSendStatus.INVALID_TOKEN, failureReason);
        }
        return PushSendResult.of(PushSendStatus.FAILED, failureReason);
    }

    /** APNs 응답에서 사후 추적용 사유를 만든다. 토큰 등 민감 정보는 포함하지 않는다. */
    private String buildApnsFailureReason(int statusCode, String responseBody) {
        String reason = extractApnsReason(responseBody);
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

    private PushSendResult sendFcm(String fcmToken, String title, String body) throws Exception {
        if (!fcmEnabled) {
            log.debug("FCM disabled, skipping send");
            return PushSendResult.of(PushSendStatus.SKIPPED, "FCM_DISABLED");
        }

        Message message = Message.builder()
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .setToken(fcmToken)
                .build();

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

    private String buildApnsPayload(String title, String body) {
        try {
            Map<String, Object> alert = Map.of("title", title, "body", body);
            Map<String, Object> aps = Map.of("alert", alert, "sound", "default");
            return objectMapper.writeValueAsString(Map.of("aps", aps));
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
