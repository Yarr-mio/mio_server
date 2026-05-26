package com.mio.notification.scheduler;

import com.mio.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProactiveCareJob {

    private final NotificationService notificationService;

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void run() {
        notificationService.processScheduledNotifications();
    }
}
