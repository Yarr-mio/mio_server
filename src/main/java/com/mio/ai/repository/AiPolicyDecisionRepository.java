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

    // llm_cost_usd 가 숫자가 아닌 값(예: 파싱 실패로 문자열이 남는 경우)이면 ::numeric 캐스팅이
    // 예외를 던져 전체 세션 조회가 500 으로 죽는다. jsonb_typeof 로 숫자 행만 걸러 방어한다.
    @Query(value = """
            SELECT COALESCE(SUM((trace->>'llm_cost_usd')::numeric), 0)
            FROM ai_policy_decisions
            WHERE session_id = :sessionId
              AND jsonb_typeof(trace->'llm_cost_usd') = 'number'
            """, nativeQuery = true)
    BigDecimal sumCostUsdBySessionId(@Param("sessionId") UUID sessionId);
}
