package com.mio.ai.cost;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface AiCostEventRepository extends JpaRepository<AiCostEvent, UUID> {

    // SUM(e.costUsd)는 cost_usd=null(단가 미등록) 행을 조용히 건너뛴다 — unpricedCount 없이
    // 합계만 반환하면 "일부만 더한 값"이 "전체 비용"처럼 보인다(이슈 #431 리뷰, AiCostAggregate 참고).
    @Query("SELECT new com.mio.ai.cost.AiCostAggregate("
            + "COALESCE(SUM(e.costUsd), 0), "
            + "SUM(CASE WHEN e.costUsd IS NULL THEN 1 ELSE 0 END), "
            + "COUNT(e)) "
            + "FROM AiCostEvent e WHERE e.sessionId = :sessionId")
    AiCostAggregate aggregateBySessionId(@Param("sessionId") UUID sessionId);

    @Query("SELECT new com.mio.ai.cost.AiCostAggregate("
            + "COALESCE(SUM(e.costUsd), 0), "
            + "SUM(CASE WHEN e.costUsd IS NULL THEN 1 ELSE 0 END), "
            + "COUNT(e)) "
            + "FROM AiCostEvent e WHERE e.userId = :userId AND e.createdAt >= :from AND e.createdAt < :to")
    AiCostAggregate aggregateByUserIdAndCreatedAtBetween(
            @Param("userId") UUID userId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
