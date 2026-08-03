package com.mio.ai.repository;

import com.mio.ai.domain.AiPolicyDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface AiPolicyDecisionRepository extends JpaRepository<AiPolicyDecision, UUID> {

    List<AiPolicyDecision> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    @Query(value = """
            SELECT COALESCE(SUM((trace->>'llm_cost_usd')::numeric), 0)
            FROM ai_policy_decisions
            WHERE session_id = :sessionId
            """, nativeQuery = true)
    BigDecimal sumCostUsdBySessionId(@Param("sessionId") UUID sessionId);
}
