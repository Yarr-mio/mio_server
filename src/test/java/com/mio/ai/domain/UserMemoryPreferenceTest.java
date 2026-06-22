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
