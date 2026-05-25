package com.mio.todo.domain;

import com.mio.checkin.domain.Checkin;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "behavior_tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BehaviorTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_session_id")
    private Session sourceSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_checkin_id")
    private Checkin sourceCheckin;

    /** chat / checkin / pattern / character / template */
    @Column(name = "generated_from", nullable = false)
    private String generatedFrom;

    @Column(name = "action_text", nullable = false)
    private String actionText;

    /** 심리_안정 / 인지_재구성 / 행동_활성화 */
    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "difficulty")
    private Integer difficulty;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    @Column(name = "character_id")
    private String characterId;

    /** suggested / completed / skipped / expired */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "suggested";

    /** CBT 측정용 0~100 */
    @Column(name = "before_emotion")
    private Integer beforeEmotion;

    /** CBT 측정용 0~100 */
    @Column(name = "after_emotion")
    private Integer afterEmotion;

    @Column(name = "feedback")
    private String feedback;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public void complete(Integer beforeEmotion, Integer afterEmotion, String feedback) {
        if (!"suggested".equals(this.status)) {
            throw new BusinessException(ErrorCode.TODO_ALREADY_COMPLETED);
        }
        validateEmotionRange(beforeEmotion);
        validateEmotionRange(afterEmotion);
        this.status = "completed";
        this.beforeEmotion = beforeEmotion;
        this.afterEmotion = afterEmotion;
        this.feedback = feedback;
        this.completedAt = OffsetDateTime.now(AppConstants.ZONE);
    }

    public void skip() {
        if (!"suggested".equals(this.status)) {
            throw new BusinessException(ErrorCode.TODO_ALREADY_COMPLETED);
        }
        this.status = "skipped";
        this.completedAt = OffsetDateTime.now(AppConstants.ZONE);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(AppConstants.ZONE);
    }

    private void validateEmotionRange(Integer value) {
        if (value != null && (value < 0 || value > 100)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
