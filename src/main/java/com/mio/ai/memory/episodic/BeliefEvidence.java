package com.mio.ai.memory.episodic;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "belief_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BeliefEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "belief_id", nullable = false)
    private UserBelief belief;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "message_id")
    private UUID messageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thought_id")
    private Thought thought;

    @Column(name = "evidence_kind", nullable = false)
    private String evidenceKind;

    @Column(nullable = false)
    private double weight = 1.0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private BeliefEvidence(UserBelief belief, UUID sessionId, UUID messageId, Thought thought,
                           String evidenceKind, double weight) {
        this.belief = belief;
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.thought = thought;
        this.evidenceKind = evidenceKind;
        this.weight = weight;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
