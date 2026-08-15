package com.mio.admin.service;

import com.mio.admin.dto.SessionCostResponse;
import com.mio.ai.cost.AiCostAggregate;
import com.mio.ai.cost.AiCostEventRepository;
import com.mio.ai.cost.InfraCostAllocator;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 이슈 #433/#437 — AI 비용 + 인프라 배분(캐시 있으면), 캐시 없으면 인프라·합계는 null. */
@ExtendWith(MockitoExtension.class)
class AdminSessionCostServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private AiCostEventRepository aiCostEventRepository;
    @Mock private InfraCostAllocator infraCostAllocator;

    private AdminSessionCostService service;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminSessionCostService(sessionRepository, aiCostEventRepository, infraCostAllocator);
    }

    @Test
    @DisplayName("존재하지 않는 세션이면 SESSION_NOT_FOUND")
    void getCost_sessionNotFound_throws() {
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCost(sessionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("전부 단가가 있으면 direct_variable_cost_usd가 채워지고 인프라·합계는 null이다")
    void getCost_allPriced_infraStillNull() {
        User user = User.builder().id(userId).build();
        Session session = Session.builder().id(sessionId).user(user).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(aiCostEventRepository.aggregateBySessionId(sessionId))
                .thenReturn(new AiCostAggregate(new BigDecimal("0.013"), 0L, 2L));

        SessionCostResponse response = service.getCost(sessionId);

        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.directVariableCostUsd()).isEqualByComparingTo(new BigDecimal("0.013"));
        assertThat(response.unpricedEvents()).isZero();
        assertThat(response.allPriced()).isTrue();
        assertThat(response.allocatedFixedInfraUsdEstimate())
                .as("인프라 비용 배치 캐시가 없으면 0이 아니라 null이어야 '인프라 비용 없음'으로 오독되지 않는다")
                .isNull();
        assertThat(response.totalUsd()).isNull();
    }

    @Test
    @DisplayName("단가 미등록 이벤트가 섞여 있으면 allPriced=false와 unpricedEvents가 그대로 노출된다")
    void getCost_partiallyUnpriced_revealsIncompleteness() {
        User user = User.builder().id(userId).build();
        Session session = Session.builder().id(sessionId).user(user).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(aiCostEventRepository.aggregateBySessionId(sessionId))
                .thenReturn(new AiCostAggregate(new BigDecimal("0.01"), 1L, 2L));

        SessionCostResponse response = service.getCost(sessionId);

        assertThat(response.allPriced()).isFalse();
        assertThat(response.unpricedEvents()).isEqualTo(1L);
    }

    @Test
    @DisplayName("인프라 배분 캐시가 있으면 총액을 AI비용+인프라비용으로 채운다")
    void getCost_infraAllocationAvailable_fillsTotal() {
        User user = User.builder().id(userId).build();
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-08-10T10:00:00+09:00");
        OffsetDateTime endedAt = OffsetDateTime.parse("2026-08-10T10:05:00+09:00");
        Session session = Session.builder()
                .id(sessionId).user(user).startedAt(startedAt).endedAt(endedAt).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(aiCostEventRepository.aggregateBySessionId(sessionId))
                .thenReturn(new AiCostAggregate(new BigDecimal("0.013"), 0L, 2L));
        when(infraCostAllocator.allocateForSession(eq(YearMonth.of(2026, 8)), eq(300L)))
                .thenReturn(new BigDecimal("0.555"));

        SessionCostResponse response = service.getCost(sessionId);

        assertThat(response.allocatedFixedInfraUsdEstimate()).isEqualByComparingTo(new BigDecimal("0.555"));
        assertThat(response.totalUsd()).isEqualByComparingTo(new BigDecimal("0.568"));
    }

    @Test
    @DisplayName("세션 길이가 0(진행 중이거나 미종료)이면 인프라 배분을 시도하지 않는다")
    void getCost_zeroDuration_skipsInfraAllocation() {
        User user = User.builder().id(userId).build();
        Session session = Session.builder().id(sessionId).user(user).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(aiCostEventRepository.aggregateBySessionId(sessionId))
                .thenReturn(new AiCostAggregate(BigDecimal.ZERO, 0L, 0L));

        service.getCost(sessionId);

        org.mockito.Mockito.verifyNoInteractions(infraCostAllocator);
    }
}
