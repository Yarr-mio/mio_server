package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "memory_embeddings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MemoryEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @Column(name = "content_summary", nullable = false)
    private String contentSummary;

    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private float[] embedding;

    @Column(name = "memory_type", nullable = false)
    private String memoryType;

    @Column(name = "sensitivity", nullable = false)
    private String sensitivity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        if (sensitivity == null) {
            sensitivity = "normal";
        }
    }
}
