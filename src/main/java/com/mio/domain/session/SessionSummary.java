package com.mio.domain.session;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "session_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SessionSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    private Session session;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "summary_text", nullable = false)
    private String summaryText;

    @Column(name = "emotion_arc", columnDefinition = "jsonb")
    private String emotionArc;

    @Column(name = "cbt_markers", columnDefinition = "jsonb")
    private String cbtMarkers;

    @Column(name = "embedding_status", nullable = false)
    private String embeddingStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        if (embeddingStatus == null) {
            embeddingStatus = "pending";
        }
    }
}
