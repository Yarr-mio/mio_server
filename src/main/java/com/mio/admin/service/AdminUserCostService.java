package com.mio.admin.service;

import com.mio.admin.dto.UserMonthlyCostResponse;
import com.mio.ai.cost.AiCostAggregate;
import com.mio.ai.cost.AiCostEventRepository;
import com.mio.ai.cost.InfraCostAllocator;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 운영자용 유저별 월간 누적 비용 조회 (이슈 #434, #437 — #433의 연장).
 *
 * <p>세션 단위였던 #433의 집계를 유저·월 단위로만 바꾼 것 — 신규 계측 없이
 * {@code AiCostEventRepository.aggregateByUserIdAndCreatedAtBetween()}만 추가로 소비한다.
 * 인프라 비용 배분(옵션 A 시간 비례)은 이슈 #437에서 채웠다 — 해당 월 캐시가 아직 없으면
 * {@code allocated_fixed_infra_usd_estimate}/{@code user_month_technical_cogs_usd}는 여전히
 * {@code null}이다(#433과 같은 이유).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserCostService {

    private final UserRepository userRepository;
    private final AiCostEventRepository aiCostEventRepository;
    private final InfraCostAllocator infraCostAllocator;

    public UserMonthlyCostResponse getMonthlyCost(UUID userId, String month) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        YearMonth yearMonth = parseOrCurrentMonth(month);
        OffsetDateTime from = yearMonth.atDay(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime to = yearMonth.plusMonths(1).atDay(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();

        AiCostAggregate aggregate = aiCostEventRepository.aggregateByUserIdAndCreatedAtBetween(userId, from, to);
        BigDecimal allocatedInfraUsd = infraCostAllocator.allocateForUserMonth(userId, yearMonth);
        BigDecimal technicalCogsUsd = allocatedInfraUsd != null
                ? aggregate.totalCostUsd().add(allocatedInfraUsd)
                : null;

        return new UserMonthlyCostResponse(
                userId,
                yearMonth.toString(),
                aggregate.totalCostUsd(),
                aggregate.unpricedCount(),
                aggregate.allPriced(),
                allocatedInfraUsd,
                technicalCogsUsd
        );
    }

    private YearMonth parseOrCurrentMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(AppConstants.ZONE);
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
