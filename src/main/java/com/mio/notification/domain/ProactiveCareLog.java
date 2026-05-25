package com.mio.notification.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "proactive_care_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProactiveCareLog {

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

    /** SENT / DELIVERED / OPENED / FAILED */
    @Column(name = "notification_status", nullable = false)
    @Builder.Default
    private String notificationStatus = "SENT";

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    /** tapped / dismissed */
    @Column(name = "response_action")
    private String responseAction;

    public void markOpened() {
        this.notificationStatus = "OPENED";
        this.respondedAt = OffsetDateTime.now();
        this.responseAction = "tapped";
    }

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = OffsetDateTime.now();
        }
    }
}
