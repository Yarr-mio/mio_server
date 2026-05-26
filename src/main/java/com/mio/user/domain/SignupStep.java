package com.mio.user.domain;

public enum SignupStep {
    SOCIAL_AUTHENTICATED,
    CONSENT_AGREED,
    PROFILE_COMPLETED,
    ONBOARDING_COMPLETED,
    COMPLETED;

    public boolean isOnboardingComplete() {
        return this == ONBOARDING_COMPLETED || this == COMPLETED;
    }
}
