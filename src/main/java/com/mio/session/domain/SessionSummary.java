package com.mio.session.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    private Session session;

    @Column(name = "character_id", nullable = false)
    private String characterId;

    @Column(name = "summary_text", nullable = false)
    private String summaryText;

    /** AES-256 암호화된 요약 원문 */
    @Column(name = "summary_ciphertext")
    private byte[] summaryCiphertext;

    @Column(name = "summary_dek_id")
    private String summaryDekId;

    /** 세션에서 가장 지배적인 감정 */
    @Column(name = "dominant_emotion")
    private String dominantEmotion;

    /** 감지된 인지 왜곡 유형 목록 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bias_types_detected", columnDefinition = "jsonb")
    @Builder.Default
    private String biasTypesDetected = "[]";

    /** CBT 개입 여부 */
    @Column(name = "cbt_intervened", nullable = false)
    private boolean cbtIntervened;

    /** pending / done / failed */
    @Column(name = "embedding_status", nullable = false)
    @Builder.Default
    private String embeddingStatus = "pending";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
