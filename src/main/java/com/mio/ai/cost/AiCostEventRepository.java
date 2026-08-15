package com.mio.ai.cost;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public interface AiCostEventRepository extends JpaRepository<AiCostEvent, UUID> {

    @Query("SELECT COALESCE(SUM(e.costUsd), 0) FROM AiCostEvent e WHERE e.sessionId = :sessionId")
    BigDecimal sumCostBySessionId(@Param("sessionId") UUID sessionId);

    @Query("SELECT COALESCE(SUM(e.costUsd), 0) FROM AiCostEvent e "
            + "WHERE e.userId = :userId AND e.createdAt >= :from AND e.createdAt < :to")
    BigDecimal sumCostByUserIdAndCreatedAtBetween(
            @Param("userId") UUID userId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
