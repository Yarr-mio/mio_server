package com.mio.domain.crisis;

import com.mio.domain.session.Session;
import com.mio.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "crisis_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CrisisEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private Session session;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Column(name = "severity", nullable = false)
    private int severity;

    @Column(name = "category")
    private String category;

    @Column(name = "resource_shown")
    private String resourceShown;

    @Column(name = "operator_reviewed", nullable = false)
    private boolean operatorReviewed;

    @Column(name = "operator_note")
    private String operatorNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
