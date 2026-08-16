package com.mio.memorycontrol.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 사용자 제공 기억 정정 이력 (이슈 #453).
 *
 * <p>정정문은 사용자 발화와 같은 민감도로 취급해 AES-256 암호화로만 저장한다.
 * 원본 기억 행은 삭제하지 않고 {@code memory_status='corrected'} 로 남는다.
 */
@Entity
@Table(name = "user_memory_corrections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** summary / episode / belief */
    @Column(name = "memory_type", nullable = false)
    private String memoryType;

    @Column(name = "memory_id", nullable = false)
    private UUID memoryId;

    @Column(name = "corrected_text_ciphertext", nullable = false)
    private byte[] correctedTextCiphertext;

    @Column(name = "corrected_text_dek_id", nullable = false)
    private String correctedTextDekId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private MemoryCorrection(UUID userId, String memoryType, UUID memoryId,
                             byte[] correctedTextCiphertext, String correctedTextDekId) {
        this.userId = userId;
        this.memoryType = memoryType;
        this.memoryId = memoryId;
        this.correctedTextCiphertext = correctedTextCiphertext;
        this.correctedTextDekId = correctedTextDekId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
