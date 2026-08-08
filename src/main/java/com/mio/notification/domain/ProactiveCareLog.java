package com.mio.notification.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "proactive_care_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProactiveCareLog {

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_OPENED = "OPENED";
    public static final String STATUS_FAILED = "FAILED";
    /** 보낼 유효 디바이스 토큰이 없어 발송 자체가 일어나지 않은 상태. */
    public static final String STATUS_NO_DEVICE = "NO_DEVICE";

    /** 실제로 단말까지 발송된 것으로 간주하는 상태 집합 (재발송 억제·일일 한도 산정 기준). */
    public static final Set<String> DELIVERED_STATUSES = Set.of(STATUS_SENT, STATUS_DELIVERED, STATUS_OPENED);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * [v2.4] CHECK 제약 7종:
     * checkin_reminder_morning / checkin_reminder_afternoon / checkin_reminder_evening /
     * todo_incomplete / negative_emotion_streak / crisis_detected / report_weekly
     */
    @Column(name = "trigger_code", nullable = false)
    private String triggerCode;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    /** SENT / DELIVERED / OPENED / FAILED / NO_DEVICE */
    @Column(name = "notification_status", nullable = false)
    @Builder.Default
    private String notificationStatus = STATUS_SENT;

    /** 발송 실패 사유 (APNs HTTP 상태·reason, FCM 오류 코드). 성공 시 null */
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    /** tapped / dismissed */
    @Column(name = "response_action")
    private String responseAction;

    public void markOpened() {
        if (STATUS_OPENED.equals(this.notificationStatus) || this.respondedAt != null) {
            return;
        }
        this.notificationStatus = STATUS_OPENED;
        this.respondedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.responseAction = "tapped";
    }

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
