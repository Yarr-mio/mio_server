package com.mio.auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class DevAuthControllerTest {

    @Test
    @DisplayName("개발용 JWT 발급 컨트롤러는 local profile과 명시 property가 모두 필요하다")
    void devAuthControllerRequiresLocalProfileAndExplicitProperty() {
        Profile profile = DevAuthController.class.getAnnotation(Profile.class);
        ConditionalOnProperty condition = DevAuthController.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local");

        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("auth.dev-token-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }
}
