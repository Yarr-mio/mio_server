package com.mio.ai.cost;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 호출 단위 LLM/embedding 비용 원장 (이슈 #431).
 *
 * <p>{@code ai_policy_decisions.trace.llm_cost_usd} 는 메인 대화 생성 호출 1건만 반영한다.
 * {@code OpenAiLlmClient.recordUsage()} 를 거치는 모든 호출부(judge·분류·추출·요약·개인화·
 * 리포트·임베딩 등)의 비용을 이 테이블 하나로 모은다 — {@code ai_policy_decisions} 는 정책결정
 * 로그 역할만 유지하고 비용 원장으로 겸용하지 않는다.
 *
 * <p>{@code userId}/{@code sessionId} 는 FK 를 걸지 않는다 — {@code audit_logs} 와 같은 이유로,
 * 유저 하드삭제 이후에도 비용 기록 자체는 보존 정책상 남아야 할 수 있다.
 */
@Entity
@Table(name = "ai_cost_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCostEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "component", nullable = false)
    private String component;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "mode", nullable = false)
    private String mode;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "cached_tokens", nullable = false)
    private long cachedTokens;

    @Column(name = "cost_usd")
    private BigDecimal costUsd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private AiCostEvent(UUID userId, UUID sessionId, String component, String model, String mode,
                         long promptTokens, long completionTokens, long cachedTokens, BigDecimal costUsd,
                         OffsetDateTime createdAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.component = component;
        this.model = model;
        this.mode = mode;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.cachedTokens = cachedTokens;
        this.costUsd = costUsd;
        this.createdAt = createdAt;
    }

    /**
     * 호출측이 실제 사용 시각을 넘겨주지 않은 경우에만 저장 시각으로 채운다(안전망) —
     * 정상 경로는 {@code OpenAiLlmClient.recordUsage()}가 사용 시각을 명시적으로 넘긴다.
     * 커밋 시각에 기대면 비동기 큐 지연만큼 실제 사용 시각과 어긋난다(이슈 #431 리뷰).
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
