package com.mio.user.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    @DisplayName("onCreate는 createdAt과 updatedAt을 UTC로 저장한다")
    void onCreate_setsUtcTimestamps() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();

        user.onCreate();

        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(user.getUpdatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("completeOnboarding은 onboardingStep을 4로 설정한다")
    void completeOnboarding_setsOnboardingStepFour() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();

        user.completeOnboarding("mio");

        assertThat(user.getOnboardingStep()).isEqualTo(4);
        assertThat(user.getPreferredCharacterId()).isEqualTo("mio");
        assertThat(user.getSignupStep()).isEqualTo(SignupStep.ONBOARDING_COMPLETED);
    }

    @Test
    @DisplayName("softDelete는 deletedAt을 UTC로 저장하고 상태를 DELETED로 바꾼다")
    void softDelete_setsUtcDeletedAt() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .email("test@example.com")
                .nickname("닉네임")
                .privacyConsent(true)
                .build();

        user.softDelete("anonymized-social-id");

        assertThat(user.getStatus()).isEqualTo("DELETED");
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getDeletedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(user.getSocialId()).isEqualTo("anonymized-social-id");
    }

    @Test
    @DisplayName("softDelete는 닉네임/이메일을 지우지 않는다 (이슈 #538: 30일 내 복구 대비)")
    void softDelete_doesNotEraseNicknameOrEmail() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .email("test@example.com")
                .nickname("닉네임")
                .privacyConsent(true)
                .build();

        user.softDelete("anonymized-social-id");

        assertThat(user.getEmail()).isEqualTo("test@example.com");
        assertThat(user.getNickname()).isEqualTo("닉네임");
    }

    @Test
    @DisplayName("restore는 탈퇴 이전 socialId를 복원하고 deletedAt을 지운다 (이슈 #538)")
    void restore_completedSignup_restoresToActive() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .email("test@example.com")
                .nickname("닉네임")
                .privacyConsent(true)
                .build();
        user.finalizeSignup();
        user.softDelete("anonymized-social-id");

        user.restore("social-id", "new@example.com");

        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        assertThat(user.getSocialId()).isEqualTo("social-id");
        assertThat(user.getDeletedAt()).isNull();
        assertThat(user.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    @DisplayName("restore는 가입을 끝내지 못하고 탈퇴한 계정을 ACTIVE가 아닌 PENDING으로 되돌린다 (이슈 #538)")
    void restore_incompleteSignup_restoresToPending() {
        User user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();
        user.softDelete("anonymized-social-id");

        user.restore("social-id", null);

        assertThat(user.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("restore는 email이 null이면 기존 이메일을 유지한다 (Apple 재동의 미제공 케이스, 이슈 #538)")
    void restore_nullEmail_keepsExistingEmail() {
        User user = User.builder()
                .socialProvider("apple")
                .socialId("social-id")
                .email("original@example.com")
                .privacyConsent(true)
                .build();
        user.softDelete("anonymized-social-id");

        user.restore("social-id", null);

        assertThat(user.getEmail()).isEqualTo("original@example.com");
    }
}
