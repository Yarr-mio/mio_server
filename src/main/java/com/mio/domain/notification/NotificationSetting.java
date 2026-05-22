package com.mio.domain.notification;

import com.mio.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class NotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "notification_agree", nullable = false)
    private boolean notificationAgree;

    @Column(name = "checkin_reminder_on", nullable = false)
    private boolean checkinReminderOn;

    @Column(name = "checkin_morning_time", nullable = false)
    private LocalTime checkinMorningTime;

    @Column(name = "checkin_afternoon_time", nullable = false)
    private LocalTime checkinAfternoonTime;

    @Column(name = "checkin_evening_time", nullable = false)
    private LocalTime checkinEveningTime;

    @Column(name = "character_message_on", nullable = false)
    private boolean characterMessageOn;

    @Column(name = "report_alert_on", nullable = false)
    private boolean reportAlertOn;

    @Column(name = "todo_reminder_on", nullable = false)
    private boolean todoReminderOn;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
