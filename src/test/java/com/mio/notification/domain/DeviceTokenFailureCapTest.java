package com.mio.notification.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 실패한 토큰의 재시도 상한 (이슈 #497).
 *
 * <p>죽은 토큰 하나가 5분마다 영구히 재시도됐다. 토큰을 <b>보존하는</b> 판단은 의도된 것이므로
 * (#411, #418 — {@code apns-topic} 설정 오류면 전 유저 토큰이 한꺼번에 무효화된다) 그대로 두고,
 * 재시도에만 상한을 건다.
 *
 * <p>그래서 이 테스트가 고정하는 핵심은 <b>"상한에 도달해도 토큰은 유효하다"</b> 이다.
 * 누군가 이 규칙을 무효화로 바꾸면 서버 설정 오류 한 번에 전 유저 푸시가 영구히 끊긴다.
 */
@DisplayName("DeviceToken — 연속 실패 상한 (#497)")
class DeviceTokenFailureCapTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 12, 0, 0, 0, ZoneOffset.UTC);

    @Nested
    @DisplayName("상한 판정")
    class Threshold {

        @Test
        void 상한_미만이면_계속_발송_대상이다() {
            DeviceToken token = newToken();
            failTimes(token, DeviceToken.MAX_CONSECUTIVE_FAILURES - 1, NOW);

            assertThat(token.isSendSuppressed(NOW)).isFalse();
        }

        @Test
        void 상한에_도달하면_발송에서_빠진다() {
            DeviceToken token = newToken();
            failTimes(token, DeviceToken.MAX_CONSECUTIVE_FAILURES, NOW);

            assertThat(token.isSendSuppressed(NOW)).isTrue();
        }

        @Test
        void 상한에_도달해도_토큰은_유효하다() {
            DeviceToken token = newToken();
            failTimes(token, DeviceToken.MAX_CONSECUTIVE_FAILURES * 3, NOW);

            // 무효화하면 apns-topic 설정 오류가 고쳐져도 스스로 회복되지 못한다.
            assertThat(token.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("쿨다운")
    class Cooldown {

        @Test
        void 쿨다운이_지나면_다시_발송_대상이_된다() {
            DeviceToken token = newToken();
            failTimes(token, DeviceToken.MAX_CONSECUTIVE_FAILURES, NOW);

            OffsetDateTime afterCooldown = NOW.plus(DeviceToken.FAILURE_COOLDOWN).plusMinutes(1);

            // 영구 제외가 아니라 쿨다운이다 — 재시도 주기가 5분에서 24시간으로 내려갈 뿐이다.
            assertThat(token.isSendSuppressed(afterCooldown)).isFalse();
        }

        @Test
        void 쿨다운_중에는_계속_빠져_있다() {
            DeviceToken token = newToken();
            failTimes(token, DeviceToken.MAX_CONSECUTIVE_FAILURES, NOW);

            OffsetDateTime withinCooldown = NOW.plus(DeviceToken.FAILURE_COOLDOWN).minusMinutes(1);

            assertThat(token.isSendSuppressed(withinCooldown)).isTrue();
        }

        @Test
        void 쿨다운_뒤_다시_실패하면_또_빠진다() {
            DeviceToken token = newToken();
            failTimes(token, DeviceToken.MAX_CONSECUTIVE_FAILURES, NOW);
            OffsetDateTime retriedAt = NOW.plus(DeviceToken.FAILURE_COOLDOWN).plusMinutes(1);

            token.recordSendFailure("APNS_400:DeviceTokenNotForTopic", retriedAt);

            assertThat(token.isSendSuppressed(retriedAt)).isTrue();
        }
    }

    @Nested
    @DisplayName("초기화")
    class Reset {

        @Test
        void 발송에_성공하면_실패_이력이_지워진다() {
            DeviceToken token = newToken();
            failTimes(token, DeviceToken.MAX_CONSECUTIVE_FAILURES, NOW);

            token.recordSendSuccess();

            assertThat(token.isSendSuppressed(NOW)).isFalse();
            assertThat(token.getConsecutiveFailureCount()).isZero();
            assertThat(token.getLastFailureReason()).isNull();
            assertThat(token.getLastFailureAt()).isNull();
        }

        @Test
        void 앱이_토큰을_재등록하면_실패_이력이_지워진다() {
            DeviceToken token = newToken();
            failTimes(token, DeviceToken.MAX_CONSECUTIVE_FAILURES, NOW);

            // 새 토큰이 등록됐다면 이전 실패는 더 이상 이 토큰의 것이 아니다.
            token.refreshToken("new-token", "1.2.3");

            assertThat(token.isSendSuppressed(NOW)).isFalse();
            assertThat(token.getConsecutiveFailureCount()).isZero();
        }
    }

    @Test
    void 실패_사유와_시각을_남긴다() {
        DeviceToken token = newToken();

        token.recordSendFailure("APNS_400:DeviceTokenNotForTopic", NOW);

        // 사후 추적용이다 — 전 토큰이 같은 사유로 상한에 닿았다면 토픽 설정 오류 신호다.
        assertThat(token.getLastFailureReason()).isEqualTo("APNS_400:DeviceTokenNotForTopic");
        assertThat(token.getLastFailureAt()).isEqualTo(NOW);
    }

    private static DeviceToken newToken() {
        return DeviceToken.builder()
                .deviceId("device-1")
                .platform("ios")
                .token("apns-token")
                .build();
    }

    private static void failTimes(DeviceToken token, int times, OffsetDateTime at) {
        for (int i = 0; i < times; i++) {
            token.recordSendFailure("APNS_400:DeviceTokenNotForTopic", at);
        }
    }
}
