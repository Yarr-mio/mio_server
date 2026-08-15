package com.mio.ai.cost;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code OpenAiLlmClient.recordUsage()} 에서 호출되는 비용 기록 지점.
 *
 * <p>{@code AuditLogService} 와 같은 원칙 — 비동기·별도 트랜잭션이라 이 기록이 실패해도
 * 실제 LLM 응답 경로(스트리밍 등)는 막지 않는다. {@code component} 가 없으면(귀속 정보를
 * 아직 안 붙인 호출부) 조용히 스킵한다 — 절반짜리 행을 만들지 않는다.
 *
 * <p>{@code aiDecisionLoggerExecutor} 를 그대로 재사용한다. 이 지점이 세션당 최대 15회
 * 호출될 수 있어 {@code AiDecisionLogger}(세션당 1회)보다 호출 빈도가 훨씬 높다 — 큐 적체가
 * 보이면 전용 executor로 분리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiCostEventWriter {

    private final AiCostEventRepository repository;

    @Async("aiDecisionLoggerExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(UUID userId, UUID sessionId, String component, String model, String mode,
                       long promptTokens, long completionTokens, long cachedTokens, BigDecimal costUsd,
                       OffsetDateTime occurredAt) {
        if (component == null || component.isBlank()) {
            log.debug("[AiCostEventWriter] component 없음, 스킵 model={} mode={}", model, mode);
            return;
        }
        try {
            repository.save(AiCostEvent.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .component(component)
                    .model(model)
                    .mode(mode)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .cachedTokens(cachedTokens)
                    .costUsd(costUsd)
                    .createdAt(occurredAt)
                    .build());
        } catch (Exception e) {
            log.warn("[AiCostEventWriter] 비용 기록 실패 component={} model={}: {}",
                    component, model, e.getMessage());
        }
    }
}
