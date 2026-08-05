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

    @Test
    @DisplayName("#320 — enum 값이 도메인 안이면 유효하다")
    void isValidPropertyValue_enumInDomain_returnsTrue() {
        assertThat(whitelist.isValidPropertyValue("profile_submitted", "employment_status", "job_seeker")).isTrue();
        assertThat(whitelist.isValidPropertyValue("profile_submitted", "age_range", "40대+")).isTrue();
    }

    @Test
    @DisplayName("#320 — enum 값이 도메인 밖이면 무효하다 (오타·구표기 등)")
    void isValidPropertyValue_enumOutOfDomain_returnsFalse() {
        assertThat(whitelist.isValidPropertyValue("profile_submitted", "employment_status", "JOB_SEEKER")).isFalse();
        assertThat(whitelist.isValidPropertyValue("profile_submitted", "age_range", "50대+")).isFalse();
        assertThat(whitelist.isValidPropertyValue("profile_submitted", "age_range", "40대")).isFalse();
    }

    @Test
    @DisplayName("#320 — null 값은 nullable property에서 항상 통과한다")
    void isValidPropertyValue_nullValue_alwaysValid() {
        assertThat(whitelist.isValidPropertyValue("chat_message_sent", "ai_emotion_score", null)).isTrue();
    }

    @Test
    @DisplayName("#320 — 타입이 안 맞으면(int 자리에 문자열) 무효하다")
    void isValidPropertyValue_wrongType_returnsFalse() {
        assertThat(whitelist.isValidPropertyValue("chat_message_sent", "message_index", "not-a-number")).isFalse();
        assertThat(whitelist.isValidPropertyValue("chat_message_sent", "message_index", 3)).isTrue();
    }

    @Test
    @DisplayName("#320 — 카탈로그에 없는 키는 무효하다")
    void isValidPropertyValue_unknownKey_returnsFalse() {
        assertThat(whitelist.isValidPropertyValue("profile_submitted", "totally_made_up_key", "x")).isFalse();
    }
}
