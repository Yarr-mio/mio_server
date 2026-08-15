package com.mio.admin.service;

import com.mio.admin.dto.UserMonthlyCostResponse;
import com.mio.ai.cost.AiCostAggregate;
import com.mio.ai.cost.AiCostEventRepository;
import com.mio.ai.cost.InfraCostAllocator;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 이슈 #434/#437 — #433과 같은 원칙(캐시 없으면 인프라 배분 null)으로 유저·월 단위 집계. */
@ExtendWith(MockitoExtension.class)
class AdminUserCostServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AiCostEventRepository aiCostEventRepository;
    @Mock private InfraCostAllocator infraCostAllocator;

    private AdminUserCostService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminUserCostService(userRepository, aiCostEventRepository, infraCostAllocator);
    }

    @Test
    @DisplayName("존재하지 않는 유저면 USER_NOT_FOUND")
    void getMonthlyCost_userNotFound_throws() {
        when(userRepository.existsById(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.getMonthlyCost(userId, "2026-08"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("잘못된 month 형식이면 INVALID_INPUT")
    void getMonthlyCost_invalidMonthFormat_throws() {
        when(userRepository.existsById(userId)).thenReturn(true);

        assertThatThrownBy(() -> service.getMonthlyCost(userId, "not-a-month"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("month를 지정하면 해당 월의 [1일 00:00, 다음달 1일 00:00) 범위로 집계한다")
    void getMonthlyCost_explicitMonth_queriesCorrectRange() {
        when(userRepository.existsById(userId)).thenReturn(true);
        when(aiCostEventRepository.aggregateByUserIdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(new AiCostAggregate(new BigDecimal("1.5"), 0L, 3L));

        UserMonthlyCostResponse response = service.getMonthlyCost(userId, "2026-08");

        assertThat(response.month()).isEqualTo("2026-08");
        assertThat(response.directVariableCostUsd()).isEqualByComparingTo(new BigDecimal("1.5"));

        ArgumentCaptor<OffsetDateTime> fromCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> toCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        org.mockito.Mockito.verify(aiCostEventRepository)
                .aggregateByUserIdAndCreatedAtBetween(eq(userId), fromCaptor.capture(), toCaptor.capture());

        YearMonth august = YearMonth.of(2026, 8);
        assertThat(fromCaptor.getValue())
                .isEqualTo(august.atDay(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime());
        assertThat(toCaptor.getValue())
                .isEqualTo(august.plusMonths(1).atDay(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime());
    }

    @Test
    @DisplayName("month를 생략하면 이번 달(Asia/Seoul 기준)로 조회한다")
    void getMonthlyCost_missingMonth_defaultsToCurrentMonth() {
        when(userRepository.existsById(userId)).thenReturn(true);
        when(aiCostEventRepository.aggregateByUserIdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(new AiCostAggregate(BigDecimal.ZERO, 0L, 0L));

        // 서비스 호출 전후로 "지금" 월을 각각 캡처한다 — 서비스 내부와 이 어설션이 YearMonth.now()를
        // 별도로 호출하므로, 월 경계(자정)에 걸리면 두 호출이 다른 달을 반환해 거짓 실패가 날 수 있다.
        YearMonth before = YearMonth.now(AppConstants.ZONE);
        UserMonthlyCostResponse response = service.getMonthlyCost(userId, null);
        YearMonth after = YearMonth.now(AppConstants.ZONE);

        assertThat(response.month()).isIn(before.toString(), after.toString());
    }

    @Test
    @DisplayName("인프라 배분 캐시가 없으면 필드는 여전히 null이다")
    void getMonthlyCost_noInfraCache_fieldsAreNull() {
        when(userRepository.existsById(userId)).thenReturn(true);
        when(aiCostEventRepository.aggregateByUserIdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(new AiCostAggregate(new BigDecimal("2.0"), 1L, 4L));
        when(infraCostAllocator.allocateForUserMonth(eq(userId), eq(YearMonth.of(2026, 8))))
                .thenReturn(null);

        UserMonthlyCostResponse response = service.getMonthlyCost(userId, "2026-08");

        assertThat(response.allPriced()).isFalse();
        assertThat(response.unpricedEvents()).isEqualTo(1L);
        assertThat(response.allocatedFixedInfraUsdEstimate()).isNull();
        assertThat(response.userMonthTechnicalCogsUsd()).isNull();
    }

    @Test
    @DisplayName("인프라 배분 캐시가 있으면 기술원가 합계를 채운다")
    void getMonthlyCost_infraAllocationAvailable_fillsTechnicalCogs() {
        when(userRepository.existsById(userId)).thenReturn(true);
        when(aiCostEventRepository.aggregateByUserIdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(new AiCostAggregate(new BigDecimal("1.5"), 0L, 3L));
        when(infraCostAllocator.allocateForUserMonth(eq(userId), eq(YearMonth.of(2026, 8))))
                .thenReturn(new BigDecimal("0.7"));

        UserMonthlyCostResponse response = service.getMonthlyCost(userId, "2026-08");

        assertThat(response.allocatedFixedInfraUsdEstimate()).isEqualByComparingTo(new BigDecimal("0.7"));
        assertThat(response.userMonthTechnicalCogsUsd()).isEqualByComparingTo(new BigDecimal("2.2"));
    }
}
