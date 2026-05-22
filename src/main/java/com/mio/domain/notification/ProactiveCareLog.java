package com.mio.domain.notification;

import com.mio.domain.user.User;
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

    @Column(name = "trigger_code", nullable = false)
    private String triggerCode;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "response_action")
    private String responseAction;

    @PrePersist
    protected void onCreate() {
        sentAt = OffsetDateTime.now();
    }
}
