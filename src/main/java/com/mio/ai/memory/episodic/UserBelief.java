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
@Table(name = "user_beliefs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBelief {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "belief_text_ciphertext", nullable = false)
    private byte[] beliefTextCiphertext;

    @Column(name = "belief_text_dek_id", nullable = false)
    private String beliefTextDekId;

    @Column(name = "belief_kind", nullable = false)
    private String beliefKind;

    private String polarity;

    @Column(name = "support_count", nullable = false)
    private int supportCount = 0;

    @Column(name = "contradict_count", nullable = false)
    private int contradictCount = 0;

    @Column(nullable = false)
    private double confidence = 0.5;

    @Column(name = "last_activated_at")
    private OffsetDateTime lastActivatedAt;

    @Column(name = "first_observed_at", nullable = false, updatable = false)
    private OffsetDateTime firstObservedAt;

    @Column(nullable = false)
    private String status = "active";

    @Column(name = "revised_to")
    private UUID revisedTo;

    @Column(nullable = false)
    private String sensitivity = "sensitive";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private UserBelief(User user, byte[] beliefTextCiphertext, String beliefTextDekId,
                       String beliefKind, String polarity) {
        this.user = user;
        this.beliefTextCiphertext = beliefTextCiphertext;
        this.beliefTextDekId = beliefTextDekId;
        this.beliefKind = beliefKind;
        this.polarity = polarity;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        firstObservedAt = now;
        lastActivatedAt = now;
    }

    public void addSupport(double weight) {
        supportCount++;
        updateConfidence();
        lastActivatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void addContradict(double weight) {
        contradictCount++;
        updateConfidence();
    }

    /** Beta 분포 평균: (α + support) / (α + support + β + contradict), α=1, β=1 */
    private void updateConfidence() {
        confidence = (1.0 + supportCount) / (2.0 + supportCount + contradictCount);
    }
}
