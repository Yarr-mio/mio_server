package com.mio.notification.service;

import org.springframework.stereotype.Component;

@Component
public class NotificationMessageMapper {

    public NotificationMessage messageFor(String triggerCode) {
        return switch (triggerCode) {
            case "checkin_reminder_morning" ->
                    new NotificationMessage("아침 체크인", "오늘 기분은 어때요? 아침 체크인을 해보세요!");
            case "checkin_reminder_afternoon" ->
                    new NotificationMessage("점심 체크인", "오늘 오전은 어떠셨나요? 체크인을 해보세요!");
            case "checkin_reminder_evening" ->
                    new NotificationMessage("저녁 체크인", "오늘 하루 수고 많으셨어요. 저녁 체크인을 해보세요!");
            case "todo_incomplete" ->
                    new NotificationMessage("오늘의 To-do", "아직 남은 할 일이 있어요. 가볍게 하나부터 해볼까요?");
            case "negative_emotion_streak" ->
                    new NotificationMessage("마음 살피기", "요즘 힘들어 보여서요. 잠깐 마음을 들여다볼까요?");
            case "report_weekly" ->
                    new NotificationMessage("주간 리포트", "이번 주 리포트가 준비됐어요. 지금 확인해보세요.");
            case "crisis_detected" ->
                    new NotificationMessage("마음이 걱정돼요", "미오가 함께할게요. 지금 대화를 시작해보세요.");
            default ->
                    new NotificationMessage("Mio 알림", "새로운 알림이 도착했어요.");
        };
    }

    public record NotificationMessage(String title, String body) {}
}
