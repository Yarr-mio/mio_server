package com.mio.notification.scheduler;

import com.mio.notification.domain.NotificationSetting;
import com.mio.notification.repository.NotificationSettingRepository;
import com.mio.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CheckinReminderScheduler {

    private final NotificationSettingRepository notificationSettingRepository;
    private final NotificationService notificationService;

    @Scheduled(cron = "${notification.cron.morning:0 0 9 * * *}", zone = "Asia/Seoul")
    public void sendMorningReminder() {
        sendCheckinReminder("checkin_reminder_morning", "아침 체크인", "오늘 기분은 어때요? 아침 체크인을 해보세요!");
    }

    @Scheduled(cron = "${notification.cron.afternoon:0 0 12 * * *}", zone = "Asia/Seoul")
    public void sendAfternoonReminder() {
        sendCheckinReminder("checkin_reminder_afternoon", "점심 체크인", "오늘 오전은 어떠셨나요? 체크인을 해보세요!");
    }

    @Scheduled(cron = "${notification.cron.evening:0 0 22 * * *}", zone = "Asia/Seoul")
    public void sendEveningReminder() {
        sendCheckinReminder("checkin_reminder_evening", "저녁 체크인", "오늘 하루 수고 많으셨어요. 저녁 체크인을 해보세요!");
    }

    private void sendCheckinReminder(String triggerCode, String title, String body) {
        List<NotificationSetting> settings = notificationSettingRepository.findAllCheckinEnabled();
        log.info("Sending {} reminder to {} users", triggerCode, settings.size());

        for (NotificationSetting setting : settings) {
            try {
                notificationService.sendCheckinReminder(setting.getUser(), triggerCode, title, body);
            } catch (Exception e) {
                log.error("Failed to send {} to userId={}: {}", triggerCode, setting.getUser().getId(), e.getMessage());
            }
        }
    }
}
