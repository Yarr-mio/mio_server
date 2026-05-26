package com.mio.ai.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * AI Memory 원본 메모리 이벤트 (AES-256 암호화 필수)
 */
@Entity
@Table(name = "user_memory_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserMemoryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id")
    private UUID sessionId;

    /** chat / checkin / todo_result / report / crisis */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "content_ciphertext", nullable = false)
    private byte[] contentCiphertext;

    @Column(name = "content_dek_id", nullable = false)
    private String contentDekId;

    /** normal / sensitive / restricted */
    @Column(name = "sensitivity", nullable = false)
    @Builder.Default
    private String sensitivity = "normal";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
