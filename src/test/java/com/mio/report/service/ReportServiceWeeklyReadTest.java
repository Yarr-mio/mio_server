package com.mio.report.service;

import com.mio.checkin.repository.CheckinRepository;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.report.domain.NarrativeStatus;
import com.mio.report.domain.ReportWeek;
import com.mio.report.domain.WeeklyReport;
import com.mio.report.dto.MonthlyReportResponse;
import com.mio.report.dto.WeeklyReportResponse;
import com.mio.report.repository.WeeklyReportRepository;
import com.mio.session.repository.MessageRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 주간 리포트 조회가 <b>LLM 을 부르지 않고</b> 배치 저장분만 읽는지 고정한다 (이슈 #419).
 *
 * <p>이전 구현은 조회할 때마다 내러티브를 생성했다. 같은 주차를 100번 열면 LLM 이 100번
 * 호출되면서 문장이 매번 달라졌고, 정작 API 명세는 "항상 null" 이라고 안내해 FE 가 그 값을
 * 표시하지 않았다 — 비용만 나가고 아무에게도 닿지 않는 호출이었다.
 *
 * <p>되돌림 방지는 테스트가 아니라 구조로 건다 — {@code ReportService} 에서
 * {@code ReportNarrativeService} 의존 자체를 걷어냈다. "저장분이 없을 때만 생성" 을
 * 되살리려면 생성자를 바꿔야 하므로 리뷰에서 반드시 눈에 띈다. 여기서는 관측 가능한
 * 계약(내러티브 출처와 {@code narrative_status} 판정)을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReportService — 주간 조회는 저장분만 읽는다 (#419)")
class ReportServiceWeeklyReadTest {

    /** 주간 최소 3회, 월간 최소 7회를 모두 넘기는 값. */
    private static final int ENOUGH_CHECKINS = 10;

    @Mock private CheckinRepository checkinRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private SessionSummaryRepository sessionSummaryRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private BehaviorTaskRepository behaviorTaskRepository;
    @Mock private UserRepository userRepository;
    @Mock private WeeklyReportRepository weeklyReportRepository;
    @Mock private PlatformTransactionManager txManager;

    private ReportService service;
    private UUID userId;
    private LocalDate lastWeekStart;
    private LocalDate lastMonthStart;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        lastWeekStart = ReportWeek.lastWeekStartFrom(LocalDate.now(AppConstants.ZONE));
        lastMonthStart = LocalDate.now(AppConstants.ZONE).minusMonths(1).withDayOfMonth(1);

        service = new ReportService(checkinRepository, messageRepository, sessionSummaryRepository,
                sessionRepository, behaviorTaskRepository, userRepository, weeklyReportRepository, txManager);

        // @PostConstruct 가 만드는 readOnlyTx 를 콜백 실행만 하는 스텁으로 대체한다.
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        ReflectionTestUtils.setField(service, "readOnlyTx", tx);

        when(userRepository.existsById(userId)).thenReturn(true);
        when(checkinRepository.countByUser_IdAndCheckinDateBetween(eq(userId), any(), any()))
                .thenReturn((long) ENOUGH_CHECKINS);
    }

    @Nested
    @DisplayName("내러티브 출처")
    class NarrativeSource {

        @Test
        void 저장분이_있으면_그_값을_그대로_반환한다() {
            storedReport(lastWeekStart, "이번 주는 불안이 줄었어요.", "다음 주엔 산책을 늘려봐요.");

            WeeklyReportResponse response = service.getWeeklyReport(userId, lastWeekStart);

            assertThat(response.narrative()).isEqualTo("이번 주는 불안이 줄었어요.");
            assertThat(response.coachingDirection()).isEqualTo("다음 주엔 산책을 늘려봐요.");
            assertThat(response.status()).isEqualTo(WeeklyReport.STATUS_GENERATED);
            assertThat(response.narrativeStatus()).isEqualTo(NarrativeStatus.READY);
        }

        @Test
        void 저장분이_없으면_내러티브는_null_이다() {
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            WeeklyReportResponse response = service.getWeeklyReport(userId, lastWeekStart);

            assertThat(response.narrative()).isNull();
            assertThat(response.coachingDirection()).isNull();
        }

        @Test
        void 집계_필드는_저장분과_무관하게_항상_채운다() {
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            WeeklyReportResponse response = service.getWeeklyReport(userId, lastWeekStart);

            // 내러티브가 없다는 이유로 싼 집계까지 막지 않는다.
            assertThat(response.checkinCount()).isEqualTo(ENOUGH_CHECKINS);
            assertThat(response.sessionSummary()).isNotNull();
            assertThat(response.todoSummary()).isNotNull();
        }
    }

    @Nested
    @DisplayName("narrative_status 판정 — core status 와 독립")
    class NarrativeStatusRule {

        @Test
        void 지난주인데_아직_안_채워졌으면_pending() {
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            WeeklyReportResponse response = service.getWeeklyReport(userId, lastWeekStart);

            assertThat(response.narrativeStatus()).isEqualTo(NarrativeStatus.PENDING);
        }

        @Test
        void 진행_중인_이번_주도_pending_이다() {
            LocalDate currentWeek = lastWeekStart.plusWeeks(1);
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            WeeklyReportResponse response = service.getWeeklyReport(userId, currentWeek);

            // 이번 주는 다음 월요일 배치가 채운다 — "기다리면 채워지는" 구간이다.
            assertThat(response.narrativeStatus()).isEqualTo(NarrativeStatus.PENDING);
        }

        @Test
        void 진행_중인_이번_주는_부분_집계로_표시된다() {
            LocalDate currentWeek = lastWeekStart.plusWeeks(1);
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            WeeklyReportResponse response = service.getWeeklyReport(userId, currentWeek);

            // 아직 끝나지 않은 구간을 집계한 값이라, 완전한 한 주로 오해하면 안 된다.
            assertThat(response.isPartial()).isTrue();
        }

        @Test
        void 완료된_주차는_부분_집계가_아니다() {
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            assertThat(service.getWeeklyReport(userId, lastWeekStart).isPartial()).isFalse();
        }

        @Test
        void 더_과거_주차는_영영_안_채워지므로_unavailable() {
            LocalDate olderWeek = lastWeekStart.minusWeeks(3);
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            WeeklyReportResponse response = service.getWeeklyReport(userId, olderWeek);

            // pending 으로 두면 FE 가 과거 리포트에서 무한히 로딩 UI 를 띄운다.
            assertThat(response.narrativeStatus()).isEqualTo(NarrativeStatus.UNAVAILABLE);
        }

        @Test
        void 내러티브가_없어도_core_status_는_GENERATED_다() {
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            WeeklyReportResponse response = service.getWeeklyReport(userId, lastWeekStart);

            // 내러티브 부재가 리포트 전체를 PENDING 으로 만들면 집계까지 못 그린다 (명세 v1.1).
            assertThat(response.status()).isEqualTo(WeeklyReport.STATUS_GENERATED);
            assertThat(response.checkinCount()).isEqualTo(ENOUGH_CHECKINS);
        }
    }

    @Nested
    @DisplayName("조회 범위")
    class LookbackRange {

        @Test
        void 범위를_벗어난_과거_주차는_거절한다() {
            LocalDate tooOld = lastWeekStart.minusWeeks(26);

            assertThatThrownBy(() -> service.getWeeklyReport(userId, tooOld))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_PERIOD_OUT_OF_RANGE);
        }

        @Test
        void 범위_경계는_허용한다() {
            LocalDate oldest = lastWeekStart.minusWeeks(25);
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            assertThat(service.getWeeklyReport(userId, oldest)).isNotNull();
        }

        @Test
        void 아직_시작하지_않은_주차는_거절한다() {
            LocalDate future = lastWeekStart.plusWeeks(2);

            assertThatThrownBy(() -> service.getWeeklyReport(userId, future))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_PERIOD_OUT_OF_RANGE);
        }
    }

    @Nested
    @DisplayName("월간 — 저장소가 없어 내러티브는 항상 없고, 범위만 제한한다")
    class Monthly {

        @Test
        void 내러티브는_항상_null_이고_unavailable_이다() {
            MonthlyReportResponse response = service.getMonthlyReport(userId, lastMonthStart);

            // monthly_reports 저장소가 없다. 여기서 생성하면 "GET 에서 LLM 을 부르지 않는다" 는
            // 계약을 월간만 깨게 된다.
            assertThat(response.narrative()).isNull();
            assertThat(response.coachingDirection()).isNull();
            assertThat(response.narrativeStatus()).isEqualTo(NarrativeStatus.UNAVAILABLE);
        }

        @Test
        void 집계_필드는_정상_반환한다() {
            MonthlyReportResponse response = service.getMonthlyReport(userId, lastMonthStart);

            assertThat(response.checkinCount()).isEqualTo(ENOUGH_CHECKINS);
            assertThat(response.sessionSummary()).isNotNull();
        }

        @Test
        void 범위를_벗어난_과거_월은_거절한다() {
            LocalDate tooOld = lastMonthStart.minusMonths(6);

            assertThatThrownBy(() -> service.getMonthlyReport(userId, tooOld))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_PERIOD_OUT_OF_RANGE);
        }

        @Test
        void 범위_경계는_허용한다() {
            assertThat(service.getMonthlyReport(userId, lastMonthStart.minusMonths(5))).isNotNull();
        }

        @Test
        void 아직_시작하지_않은_월은_거절한다() {
            assertThatThrownBy(() -> service.getMonthlyReport(userId, lastMonthStart.plusMonths(2)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REPORT_PERIOD_OUT_OF_RANGE);
        }

        @Test
        void 월_중간_날짜는_그_달의_1일로_맞춘다() {
            MonthlyReportResponse response = service.getMonthlyReport(userId, lastMonthStart.plusDays(15));

            assertThat(response.monthStart()).isEqualTo(lastMonthStart);
        }
    }

    @Nested
    @DisplayName("인지왜곡 집계 — session_summaries 기준 (#502)")
    class DistortionSource {

        @Test
        void 세션요약에만_있고_메시지에는_없어도_리포트에_반영된다() {
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());
            when(sessionSummaryRepository.findBiasTypeDistribution(eq(userId), any(), any()))
                    .thenReturn(java.util.Collections.singletonList(new Object[]{"overgeneralization", 2L}));

            WeeklyReportResponse response = service.getWeeklyReport(userId, lastWeekStart);

            assertThat(response.distortionTop3()).hasSize(1);
            assertThat(response.distortionTop3().get(0).type()).isEqualTo("overgeneralization");
            assertThat(response.distortionTop3().get(0).label()).isEqualTo("과일반화");
            assertThat(response.distortionTop3().get(0).count()).isEqualTo(2L);
        }

        @Test
        void 세션요약_저장소를_유저와_기간으로_조회한다() {
            when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), any()))
                    .thenReturn(Optional.empty());

            service.getWeeklyReport(userId, lastWeekStart);

            org.mockito.Mockito.verify(sessionSummaryRepository)
                    .findBiasTypeDistribution(eq(userId), any(), any());
        }
    }

    @Test
    void 월요일이_아닌_입력은_그_주의_월요일로_맞춘다() {
        LocalDate thursdayOfLastWeek = lastWeekStart.plusDays(3);
        storedReport(lastWeekStart, "정규화 확인", null);

        WeeklyReportResponse response = service.getWeeklyReport(userId, thursdayOfLastWeek);

        // week_start 에는 월요일만 저장되므로, 정규화하지 않으면 저장분과 영영 매칭되지 않는다.
        assertThat(response.weekStart()).isEqualTo(lastWeekStart);
        assertThat(response.narrative()).isEqualTo("정규화 확인");
    }

    private void storedReport(LocalDate weekStart, String narrative, String coachingDirection) {
        WeeklyReport report = WeeklyReport.builder()
                .weekStart(weekStart)
                .weekEnd(ReportWeek.weekEndOf(weekStart))
                .checkinCount(ENOUGH_CHECKINS)
                .narrative(narrative)
                .coachingDirection(coachingDirection)
                .status(WeeklyReport.STATUS_GENERATED)
                .generatedAt(OffsetDateTime.now())
                .build();
        when(weeklyReportRepository.findByUser_IdAndWeekStart(eq(userId), eq(weekStart)))
                .thenReturn(Optional.of(report));
    }
}
