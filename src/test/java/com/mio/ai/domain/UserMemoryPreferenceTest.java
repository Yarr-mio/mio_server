package com.mio.ai.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryPreferenceTest {

    @Test
    @DisplayName("preferredTone이 null이면 seedPreferredTone이 값을 채운다")
    void seedPreferredTone_whenNull_seeds() {
        UserMemoryPreference preference = UserMemoryPreference.builder()
                .userId(UUID.randomUUID())
                .build();

        preference.seedPreferredTone("empathetic");

        assertThat(preference.getPreferredTone()).isEqualTo("empathetic");
    }

    @Test
    @DisplayName("null 또는 빈 문자열이 입력되면 스킵으로 간주하여 시딩하지 않는다")
    void seedPreferredTone_nullOrBlank_doesNotSeed() {
        UserMemoryPreference preference = UserMemoryPreference.builder()
                .userId(UUID.randomUUID())
                .build();

        preference.seedPreferredTone(null);
        assertThat(preference.getPreferredTone()).isNull();

        preference.seedPreferredTone("   ");
        assertThat(preference.getPreferredTone()).isNull();
    }

    @Test
    @DisplayName("preferredTone이 이미 설정되어 있으면 덮어쓰지 않는다")
    void seedPreferredTone_alreadySet_doesNotOverwrite() {
        UserMemoryPreference preference = UserMemoryPreference.builder()
                .userId(UUID.randomUUID())
                .preferredTone("analytical")
                .build();

        preference.seedPreferredTone("empathetic");

        assertThat(preference.getPreferredTone()).isEqualTo("analytical");
    }
}
