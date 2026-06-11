package com.mio.ai.memory.episodic;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "intervention_outcomes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterventionOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "intervention_kind", nullable = false)
    private String interventionKind;

    private String target;

    @Column(name = "pre_emotion_score")
    private Integer preEmotionScore;

    @Column(name = "post_emotion_score")
    private Integer postEmotionScore;

    private Integer delta;

    @Column(name = "user_reaction")
    private String userReaction;

    @Column(name = "belief_id")
    private UUID beliefId;

    @Column(name = "behavior_task_id")
    private UUID behaviorTaskId;

    @Column(name = "character_id")
    private String characterId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private InterventionOutcome(User user, UUID sessionId, UUID behaviorTaskId,
                                String interventionKind, String target,
                                Integer preEmotionScore, Integer postEmotionScore,
                                String userReaction, String characterId) {
        this.user = user;
        this.sessionId = sessionId;
        this.behaviorTaskId = behaviorTaskId;
        this.interventionKind = interventionKind;
        this.target = target;
        this.preEmotionScore = preEmotionScore;
        this.postEmotionScore = postEmotionScore;
        this.delta = (preEmotionScore != null && postEmotionScore != null)
                ? postEmotionScore - preEmotionScore : null;
        this.userReaction = userReaction;
        this.characterId = characterId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
