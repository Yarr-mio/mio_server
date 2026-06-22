package com.mio.ai.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserSelfModelTest {

    @Test
    @DisplayName("version=1이고 필드가 비어있을 때 seedFromOnboarding이 값을 채운다")
    void seedFromOnboarding_version1_empty_seeds() {
        UserSelfModel model = UserSelfModel.builder().build();

        model.seedFromOnboarding(List.of("anxious"), List.of("career", "family"));

        assertThat(model.getDominantEmotions()).containsExactly("anxious");
        assertThat(model.getRecurringTriggerTags()).containsExactly("career", "family");
    }

    @Test
    @DisplayName("version=1이지만 dominantEmotions가 이미 채워져 있으면 덮어쓰지 않는다")
    void seedFromOnboarding_alreadyPopulated_doesNotOverwrite() {
        UserSelfModel model = UserSelfModel.builder()
                .dominantEmotions(List.of("calm"))
                .recurringTriggerTags(List.of("health"))
                .build();

        model.seedFromOnboarding(List.of("anxious"), List.of("career"));

        assertThat(model.getDominantEmotions()).containsExactly("calm");
        assertThat(model.getRecurringTriggerTags()).containsExactly("health");
    }

    @Test
    @DisplayName("version>1이면 seedFromOnboarding이 아무것도 변경하지 않는다")
    void seedFromOnboarding_higherVersion_skips() {
        UserSelfModel model = UserSelfModel.builder()
                .dominantEmotions(List.of())
                .recurringTriggerTags(List.of())
                .version(2)
                .build();

        model.seedFromOnboarding(List.of("anxious"), List.of("career"));

        assertThat(model.getDominantEmotions()).isEmpty();
        assertThat(model.getRecurringTriggerTags()).isEmpty();
    }
}
