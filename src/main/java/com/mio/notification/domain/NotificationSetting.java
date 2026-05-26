package com.mio.notification.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    @Builder.Default
    private boolean notificationAgree = true;

    /** [v2.4] checkin_reminder_on → checkin_enabled */
    @Column(name = "checkin_enabled", nullable = false)
    @Builder.Default
    private boolean checkinEnabled = true;

    @Column(name = "checkin_morning_time", nullable = false)
    @Builder.Default
    private LocalTime checkinMorningTime = LocalTime.of(9, 0);

    @Column(name = "checkin_afternoon_time", nullable = false)
    @Builder.Default
    private LocalTime checkinAfternoonTime = LocalTime.of(12, 0);

    @Column(name = "checkin_evening_time", nullable = false)
    @Builder.Default
    private LocalTime checkinEveningTime = LocalTime.of(22, 0);

    /** [v2.4] character_message_on → character_enabled */
    @Column(name = "character_enabled", nullable = false)
    @Builder.Default
    private boolean characterEnabled = true;

    /** [v2.4] report_alert_on → report_enabled */
    @Column(name = "report_enabled", nullable = false)
    @Builder.Default
    private boolean reportEnabled = true;

    /** character_enabled 카테고리에 포함 (별도 컬럼 유지) */
    @Column(name = "todo_reminder_on", nullable = false)
    @Builder.Default
    private boolean todoReminderOn = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public void update(Boolean notificationAgree, Boolean checkinEnabled,
                       LocalTime checkinMorningTime, LocalTime checkinAfternoonTime,
                       LocalTime checkinEveningTime, Boolean characterEnabled,
                       Boolean reportEnabled, Boolean todoReminderOn) {
        if (notificationAgree != null) this.notificationAgree = notificationAgree;
        if (checkinEnabled != null) this.checkinEnabled = checkinEnabled;
        if (checkinMorningTime != null) this.checkinMorningTime = checkinMorningTime;
        if (checkinAfternoonTime != null) this.checkinAfternoonTime = checkinAfternoonTime;
        if (checkinEveningTime != null) this.checkinEveningTime = checkinEveningTime;
        if (characterEnabled != null) this.characterEnabled = characterEnabled;
        if (reportEnabled != null) this.reportEnabled = reportEnabled;
        if (todoReminderOn != null) this.todoReminderOn = todoReminderOn;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
