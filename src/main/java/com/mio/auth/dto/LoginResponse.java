package com.mio.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.user.domain.SignupStep;

import java.time.OffsetDateTime;

/**
 * 로그인 응답. {@code status}/{@code withdrawn_at}/{@code recoverable_until}은 탈퇴 30일 이내
 * 계정 재로그인 감지 시에만 채워진다(이슈 #538) — 이 경우 토큰류는 전부 null이므로 나머지
 * 필드도 boxed 타입으로 바꿨다({@code @JsonInclude(NON_NULL)}이 실제로 생략하려면 primitive로는
 * 안 된다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") Integer expiresIn,
        @JsonProperty("is_new_user") Boolean isNewUser,
        @JsonProperty("is_new_device") Boolean isNewDevice,
        @JsonProperty("signup_step") SignupStep signupStep,
        @JsonProperty("onboarding_step") Integer onboardingStep,
        UserInfo user,
        String status,
        @JsonProperty("withdrawn_at") OffsetDateTime withdrawnAt,
        @JsonProperty("recoverable_until") OffsetDateTime recoverableUntil
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserInfo(
            String id,
            String nickname,
            @JsonProperty("preferred_character_id") String preferredCharacterId,
            @JsonProperty("is_minor") boolean isMinor,
            @JsonProperty("is_premium") boolean isPremium,
            String status
    ) {
    }

    /** 탈퇴 30일 이내 계정 감지 — 토큰 미발급, 복구 확인 모달 트리거용 (이슈 #538). */
    public static LoginResponse withdrawnRecoverable(OffsetDateTime withdrawnAt, OffsetDateTime recoverableUntil) {
        return new LoginResponse(null, null, null, null, null, null, null, null,
                "WITHDRAWN_RECOVERABLE", withdrawnAt, recoverableUntil);
    }
}