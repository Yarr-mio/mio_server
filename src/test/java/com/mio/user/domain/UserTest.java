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
        assertThat(user.getEmail()).isNull();
        assertThat(user.getNickname()).isEqualTo("탈퇴한 사용자");
        assertThat(user.getSocialId()).isEqualTo("anonymized-social-id");
    }
}
