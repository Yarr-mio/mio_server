package com.mio.admin.service;

import com.mio.admin.dto.SessionCostResponse;
import com.mio.ai.cost.AiCostAggregate;
import com.mio.ai.cost.AiCostEventRepository;
import com.mio.ai.cost.InfraCostAllocator;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

/**
 * 운영자용 세션당 비용 조회 (이슈 #433, #437 — #431의 후속).
 *
 * <p>AI 비용({@code ai_cost_events} 합산) + 비-AI 인프라 비용 배분(옵션 A 시간 비례, 이슈 #437)을
 * 합쳐 반환한다. 인프라 배치({@code InfraCostSyncJob})가 그 세션이 속한 달을 아직 한 번도 캐싱하지
 * 않았으면 {@code allocated_fixed_infra_usd_estimate}/{@code total_usd}는 여전히 {@code null}이다 —
 * 0으로 채우면 "인프라 비용이 없다"가 되고, 뒤늦게 채워져도 과거 조회 결과와 구분이 안 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminSessionCostService {

    private final SessionRepository sessionRepository;
    private final AiCostEventRepository aiCostEventRepository;
    private final InfraCostAllocator infraCostAllocator;

    public SessionCostResponse getCost(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        AiCostAggregate aggregate = aiCostEventRepository.aggregateBySessionId(sessionId);

        BigDecimal allocatedInfraUsd = null;
        long durationSeconds = session.durationSeconds();
        if (durationSeconds > 0) {
            YearMonth sessionMonth = YearMonth.from(session.getStartedAt().atZoneSameInstant(AppConstants.ZONE));
            allocatedInfraUsd = infraCostAllocator.allocateForSession(sessionMonth, durationSeconds);
        }
        BigDecimal totalUsd = allocatedInfraUsd != null
                ? aggregate.totalCostUsd().add(allocatedInfraUsd)
                : null;

        return new SessionCostResponse(
                sessionId,
                session.getUser().getId(),
                aggregate.totalCostUsd(),
                aggregate.unpricedCount(),
                aggregate.allPriced(),
                allocatedInfraUsd,
                totalUsd
        );
    }
}
