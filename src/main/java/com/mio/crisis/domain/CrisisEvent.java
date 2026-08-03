package com.mio.crisis.domain;

import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /**
     * 논리 위기 이벤트의 안정 키 (이슈 #269).
     *
     * <p>저장 재시도가 같은 위기를 두 번 기록하지 않도록 한다. 커밋은 성공했지만 응답이 유실된
     * 경우 다음 시도가 이 키로 기존 행을 찾는다. 이 컬럼 도입 이전 행은 {@code null} 이다.
     */
    @Column(name = "dedup_key")
    private UUID dedupKey;

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

    /** no_action_needed / user_contacted / escalated */
    @Column(name = "review_action")
    private String reviewAction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
