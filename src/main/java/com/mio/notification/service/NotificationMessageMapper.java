package com.mio.notification.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NotificationMessageMapper {

    /**
     * 푸시 페이로드 data 키 — FE 라우팅 계약 (이슈 #409).
     *
     * <p>iOS 는 {@code aps} 와 형제 레벨(최상위), Android 는 {@code data} 에 동일한 키가 실린다.
     */
    public static final String DATA_KEY_TYPE = "type";
    public static final String DATA_KEY_ROUTE = "route";
    public static final String DATA_KEY_SLOT = "slot";

    private static final String ROUTE_CHECKIN = "/checkin";
    private static final String ROUTE_CHAT = "/chat";
    private static final String ROUTE_TODO = "/todo";
    private static final String ROUTE_REPORT = "/report";
    private static final String ROUTE_HOME = "/home";

    private static final String SLOT_MORNING = "morning";
    private static final String SLOT_AFTERNOON = "afternoon";
    private static final String SLOT_EVENING = "evening";

    public NotificationMessage messageFor(String triggerCode) {
        return switch (triggerCode) {
            case "checkin_reminder_morning" ->
                    new NotificationMessage("아침 체크인", "오늘 기분은 어때요? 아침 체크인을 해보세요!",
                            ROUTE_CHECKIN, SLOT_MORNING);
            case "checkin_reminder_afternoon" ->
                    new NotificationMessage("점심 체크인", "오늘 오전은 어떠셨나요? 체크인을 해보세요!",
                            ROUTE_CHECKIN, SLOT_AFTERNOON);
            case "checkin_reminder_evening" ->
                    new NotificationMessage("저녁 체크인", "오늘 하루 수고 많으셨어요. 저녁 체크인을 해보세요!",
                            ROUTE_CHECKIN, SLOT_EVENING);
            case "todo_incomplete" ->
                    new NotificationMessage("오늘의 To-do", "아직 남은 할 일이 있어요. 가볍게 하나부터 해볼까요?",
                            ROUTE_TODO, null);
            // 연속 부정 감정·위기 감지는 "푸시 + 대화 시작"이 명세다
            // (MIO-Proactive-002 / MIO-Proactive-012, 05_비동기_알림.md 18.3~18.4).
            case "negative_emotion_streak" ->
                    new NotificationMessage("마음 살피기", "요즘 힘들어 보여서요. 잠깐 마음을 들여다볼까요?",
                            ROUTE_CHAT, null);
            case "crisis_detected" ->
                    new NotificationMessage("마음이 걱정돼요", "미오가 함께할게요. 지금 대화를 시작해보세요.",
                            ROUTE_CHAT, null);
            case "report_weekly" ->
                    new NotificationMessage("주간 리포트", "이번 주 리포트가 준비됐어요. 지금 확인해보세요.",
                            ROUTE_REPORT, null);
            default ->
                    new NotificationMessage("Mio 알림", "새로운 알림이 도착했어요.", ROUTE_HOME, null);
        };
    }

    /**
     * 푸시 페이로드에 실을 라우팅 data 를 만든다 (이슈 #409).
     *
     * <p>이 값이 없으면 앱은 알림 종류를 판별할 수 없어 OS 기본 동작(마지막 화면 복귀)으로 떨어진다.
     * {@code slot} 은 체크인 리마인더에만 존재하므로 없을 때는 키 자체를 넣지 않는다 — FCM data 는
     * null 값을 허용하지 않는다.
     */
    public Map<String, String> pushDataFor(String triggerCode) {
        NotificationMessage message = messageFor(triggerCode);
        Map<String, String> data = new LinkedHashMap<>();
        data.put(DATA_KEY_TYPE, triggerCode);
        data.put(DATA_KEY_ROUTE, message.route());
        if (message.slot() != null) {
            data.put(DATA_KEY_SLOT, message.slot());
        }
        return Map.copyOf(data);
    }

    /**
     * @param route 알림 탭 시 이동할 앱 경로
     * @param slot  체크인 슬롯 (체크인 리마인더 외에는 {@code null})
     */
    public record NotificationMessage(String title, String body, String route, String slot) {}
}
