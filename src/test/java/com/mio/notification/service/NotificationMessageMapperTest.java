package com.mio.notification.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NotificationMessageMapper — 푸시 라우팅 매핑 (#409)")
class NotificationMessageMapperTest {

    private final NotificationMessageMapper mapper = new NotificationMessageMapper();

    @ParameterizedTest
    @CsvSource({
            "checkin_reminder_morning,   /checkin, morning",
            "checkin_reminder_afternoon, /checkin, afternoon",
            "checkin_reminder_evening,   /checkin, evening"
    })
    @DisplayName("체크인 리마인더는 /checkin 으로 라우팅되고 슬롯을 함께 싣는다")
    void pushDataFor_checkinReminders_carryRouteAndSlot(String triggerCode, String route, String slot) {
        Map<String, String> data = mapper.pushDataFor(triggerCode);

        assertThat(data).containsExactlyInAnyOrderEntriesOf(Map.of(
                NotificationMessageMapper.DATA_KEY_TYPE, triggerCode,
                NotificationMessageMapper.DATA_KEY_ROUTE, route,
                NotificationMessageMapper.DATA_KEY_SLOT, slot
        ));
    }

    @ParameterizedTest
    @CsvSource({
            "negative_emotion_streak, /chat",
            "crisis_detected,         /chat",
            "todo_incomplete,         /todo",
            "report_weekly,           /report"
    })
    @DisplayName("체크인 외 트리거는 지정된 route 로 가고 slot 키를 넣지 않는다")
    void pushDataFor_nonCheckinTriggers_omitSlot(String triggerCode, String route) {
        Map<String, String> data = mapper.pushDataFor(triggerCode);

        assertThat(data).containsEntry(NotificationMessageMapper.DATA_KEY_TYPE, triggerCode);
        assertThat(data).containsEntry(NotificationMessageMapper.DATA_KEY_ROUTE, route);
        assertThat(data).doesNotContainKey(NotificationMessageMapper.DATA_KEY_SLOT);
    }

    @Test
    @DisplayName("미정의 trigger_code 는 /home 으로 폴백한다")
    void pushDataFor_unknownTrigger_fallsBackToHome() {
        Map<String, String> data = mapper.pushDataFor("something_new");

        assertThat(data).containsEntry(NotificationMessageMapper.DATA_KEY_ROUTE, "/home");
        assertThat(data).containsEntry(NotificationMessageMapper.DATA_KEY_TYPE, "something_new");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "checkin_reminder_morning", "checkin_reminder_afternoon", "checkin_reminder_evening",
            "todo_incomplete", "negative_emotion_streak", "crisis_detected", "report_weekly"
    })
    @DisplayName("DB CHECK 제약 7종 전부 route 가 채워진다")
    void messageFor_allTriggerCodes_haveRoute(String triggerCode) {
        NotificationMessageMapper.NotificationMessage message = mapper.messageFor(triggerCode);

        assertThat(message.route()).isNotBlank();
        assertThat(message.title()).isNotBlank();
        assertThat(message.body()).isNotBlank();
    }

    @Test
    @DisplayName("반환된 data 는 불변이라 호출자가 변조할 수 없다")
    void pushDataFor_returnsImmutableMap() {
        Map<String, String> data = mapper.pushDataFor("checkin_reminder_morning");

        assertThatThrownBy(() -> data.put("route", "/hacked"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
