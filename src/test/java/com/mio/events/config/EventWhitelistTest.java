package com.mio.events.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventWhitelistTest {

    private EventWhitelist whitelist;

    @BeforeEach
    void setUp() {
        whitelist = new EventWhitelist();
        whitelist.load();
    }

    @Test
    @DisplayName("카탈로그에 있는 이벤트는 known으로 판정한다")
    void isKnownEvent_catalogued_returnsTrue() {
        assertThat(whitelist.isKnownEvent("chat_message_sent")).isTrue();
        assertThat(whitelist.isKnownEvent("checkin_completed")).isTrue();
        assertThat(whitelist.isKnownEvent("crisis_flow_triggered")).isTrue();
    }

    @Test
    @DisplayName("카탈로그에 없는 이벤트는 unknown으로 판정한다")
    void isKnownEvent_uncatalogued_returnsFalse() {
        assertThat(whitelist.isKnownEvent("totally_made_up_event")).isFalse();
    }

    @Test
    @DisplayName("chat_message_sent의 허용 property 키를 정확히 반환한다")
    void allowedProperties_returnsExactKeys() {
        assertThat(whitelist.allowedProperties("chat_message_sent"))
                .containsExactlyInAnyOrder(
                        "chat_session_id", "message_index", "char_count",
                        "ai_emotion_score", "is_socratic", "cbt_intervention_state", "is_crisis_flagged"
                );
    }

    @Test
    @DisplayName("profile_submitted의 허용 property에 employment_status가 포함된다 (#291)")
    void allowedProperties_profileSubmitted_includesEmploymentStatus() {
        assertThat(whitelist.allowedProperties("profile_submitted"))
                .containsExactlyInAnyOrder("age_range", "gender", "employment_status");
    }

    @Test
    @DisplayName("property가 없는 이벤트(app_installed)는 빈 집합을 반환한다")
    void allowedProperties_noPropertiesEvent_returnsEmpty() {
        assertThat(whitelist.allowedProperties("app_installed")).isEmpty();
    }

    @Test
    @DisplayName("모르는 이벤트의 허용 property를 물으면 빈 집합을 반환한다")
    void allowedProperties_unknownEvent_returnsEmpty() {
        assertThat(whitelist.allowedProperties("totally_made_up_event")).isEmpty();
    }
}
