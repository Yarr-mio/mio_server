package com.mio.notification.scheduler;

import com.mio.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProactiveCareJob {

    private final NotificationService notificationService;

    /**
     * 5분 주기로 예약 알림 대상을 평가한다. (:00 :05 :10 … KST)
     *
     * <p>알림은 사용자가 설정한 시각 <b>이후 최초 실행</b>에 발송되므로,
     * 5분 배수가 아닌 시각(예: 20:01)을 설정하면 최대 5분까지 지연될 수 있다.
     * 이는 의도된 동작이며, 지연 자체를 없애려면 클라이언트에서 알림 시각 선택을
     * 5분 단위로 제한해야 한다.
     *
     * <p>실행 주기(5분)보다 {@code NotificationService#isDue} 의 판정 창(10분)이 넓기 때문에
     * 배포·재기동으로 한 틱을 건너뛰어도 다음 틱에서 보정 발송된다.
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void run() {
        notificationService.processScheduledNotifications();
    }
}
