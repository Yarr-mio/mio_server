package com.mio.ai.cost;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SUM(cost_usd)가 단가 미등록(cost_usd=null) 이벤트를 조용히 건너뛰어 부분 합계를
 * 완전한 합계처럼 보이게 하는 문제를 막는지 실제 DB에 대해 검증한다 (이슈 #431 리뷰).
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class AiCostEventRepositoryIntegrationTest {

    @Autowired
    private AiCostEventRepository repository;

    private UUID sessionId;
    private UUID userId;

    @AfterEach
    void tearDown() {
        // FK가 없는 원장이라 이 세션/유저에 귀속된 행만 직접 지운다.
        repository.findAll().stream()
                .filter(e -> e.getSessionId() != null && e.getSessionId().equals(sessionId))
                .forEach(e -> repository.deleteById(e.getId()));
    }

    @Test
    @DisplayName("단가 미등록 이벤트가 섞여 있으면 unpricedCount로 드러나고, 합계는 가격 있는 이벤트만 더한다")
    void aggregateBySessionId_revealsUnpricedEvents() {
        sessionId = UUID.randomUUID();
        userId = UUID.randomUUID();

        repository.save(AiCostEvent.builder()
                .userId(userId).sessionId(sessionId)
                .component("MAIN_GENERATION").model("gpt-4o").mode("stream")
                .promptTokens(100).completionTokens(50).cachedTokens(0)
                .costUsd(new BigDecimal("0.01"))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
        repository.save(AiCostEvent.builder()
                .userId(userId).sessionId(sessionId)
                .component("EXTRACTOR").model("gpt-5-future").mode("stream")
                .promptTokens(200).completionTokens(80).cachedTokens(0)
                .costUsd(null) // 단가 미등록 — 비용을 모른다
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());

        AiCostAggregate aggregate = repository.aggregateBySessionId(sessionId);

        assertThat(aggregate.totalCostUsd()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(aggregate.unpricedCount())
                .as("단가 미등록 이벤트가 있으면 합계가 완전하지 않다는 걸 알 수 있어야 한다")
                .isEqualTo(1L);
        assertThat(aggregate.totalCount()).isEqualTo(2L);
        assertThat(aggregate.allPriced()).isFalse();
    }

    @Test
    @DisplayName("전부 단가가 있으면 allPriced=true")
    void aggregateBySessionId_allPricedWhenNoUnpricedEvents() {
        sessionId = UUID.randomUUID();
        userId = UUID.randomUUID();

        repository.save(AiCostEvent.builder()
                .userId(userId).sessionId(sessionId)
                .component("MAIN_GENERATION").model("gpt-4o").mode("stream")
                .promptTokens(100).completionTokens(50).cachedTokens(0)
                .costUsd(new BigDecimal("0.01"))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());

        AiCostAggregate aggregate = repository.aggregateBySessionId(sessionId);

        assertThat(aggregate.allPriced()).isTrue();
        assertThat(aggregate.unpricedCount()).isZero();
    }

    @Test
    @DisplayName("이벤트가 없는 세션은 합계 0, 개수 0을 반환한다")
    void aggregateBySessionId_emptyWhenNoEvents() {
        sessionId = UUID.randomUUID();

        AiCostAggregate aggregate = repository.aggregateBySessionId(sessionId);

        assertThat(aggregate.totalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aggregate.totalCount()).isZero();
        assertThat(aggregate.allPriced()).isTrue();
    }
}
