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

    /**
     * 내부용 요약. ExtractorLLM 과 Retriever 3종이 읽는 기억 원문이라 분석 시점으로 쓰여 있다.
     * 사용자에게 그대로 노출하지 않는다 — {@link #userSummaryText} 참조.
     */
    @Column(name = "summary_text", nullable = false)
    private String summaryText;

    /**
     * 사용자 노출용 요약 (이슈 #339). 캐릭터 톤의 해요체로 렌더링한 결과.
     * 렌더링 실패·기존 세션은 null 이며, 조회 시 {@link #summaryText} 로 폴백한다.
     */
    @Column(name = "user_summary_text")
    private String userSummaryText;

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

    /** 세션 핵심 생각 목록 (JSONB) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_thoughts", columnDefinition = "jsonb")
    private String keyThoughts;

    /** 세션 내 소크라테스 질문 횟수 */
    @Column(name = "socratic_count")
    private Integer socraticCount;

    /** pending / done / failed */
    @Column(name = "embedding_status", nullable = false)
    @Builder.Default
    private String embeddingStatus = "pending";

    @Column(name = "user_render_status", nullable = false)
    @Builder.Default
    private SummaryComponentStatus userRenderStatus = SummaryComponentStatus.PENDING;

    @Column(name = "todo_status", nullable = false)
    @Builder.Default
    private SummaryComponentStatus todoStatus = SummaryComponentStatus.PENDING;

    /** 렌더링이 pending 으로 전환된 시각. NULL(마이그레이션 이전 행)은 created_at 으로 폴백한다. */
    @Column(name = "user_render_pending_at")
    private OffsetDateTime userRenderPendingAt;

    /** Todo 생성이 pending 으로 전환된 시각. NULL(마이그레이션 이전 행)은 created_at 으로 폴백한다. */
    @Column(name = "todo_pending_at")
    private OffsetDateTime todoPendingAt;

    /** 컴포넌트명 → 내부 상세를 제외한 기계 판독 오류 코드. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "component_errors", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String componentErrors = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (userRenderPendingAt == null) {
            userRenderPendingAt = now;
        }
        if (todoPendingAt == null) {
            todoPendingAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
