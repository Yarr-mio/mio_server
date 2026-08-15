package com.mio.admin.service;

import com.mio.admin.dto.SessionCostResponse;
import com.mio.ai.cost.AiCostAggregate;
import com.mio.ai.cost.AiCostEventRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 운영자용 세션당 비용 조회 (이슈 #433, #431의 후속).
 *
 * <p>AI 비용({@code ai_cost_events} 합산)만 반영한다. 비-AI 인프라 비용 배분(AWS Cost Explorer
 * 연동, 옵션 A 시간 비례)은 별도 이슈에서 채운다 — 그 전까지 {@code allocated_fixed_infra_usd_estimate}/
 * {@code total_usd}는 {@code null}로 비워 "인프라 비용 미지원"을 명시적으로 드러낸다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminSessionCostService {

    private final SessionRepository sessionRepository;
    private final AiCostEventRepository aiCostEventRepository;

    public SessionCostResponse getCost(UUID sessionId) {
        var session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        AiCostAggregate aggregate = aiCostEventRepository.aggregateBySessionId(sessionId);

        // AWS Cost Explorer 연동 전까지는 인프라 배분을 모른다 — 0이 아니라 null로 비운다.
        // 0으로 채우면 "인프라 비용이 없다"가 되고, 뒤늦게 연동해도 과거 조회 결과와 구분이 안 된다.
        return new SessionCostResponse(
                sessionId,
                session.getUser().getId(),
                aggregate.totalCostUsd(),
                aggregate.unpricedCount(),
                aggregate.allPriced(),
                null,
                null
        );
    }
}
