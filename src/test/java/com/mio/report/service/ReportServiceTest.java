package com.mio.report.service;

import com.mio.checkin.repository.CheckinRepository;
import com.mio.report.dto.WeeklyReportResponse;
import com.mio.session.repository.CbtReconstructionRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 리포트의 평균 감정 점수 소스가 CBT 사용자 입력값인지 고정한다 (이슈 #540).
 *
 * <p>{@code weekly_reports.avg_emotion_score}(배치가 채우는 값)는 이 API 응답 경로에서
 * 전혀 읽히지 않는다 — {@code GET /v1/reports/weekly}는 매 요청마다 즉석으로 재계산한다.
 * 이전에는 여기서 {@code messages.emotion_score}(AI 내부 키워드 신호)를 읽어, 정책상
 * 사용자에게 노출하면 안 되는 값이 그대로 나가고 있었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReportService — 평균 감정 점수 소스 (#540)")
class ReportServiceTest {

    @Mock private CheckinRepository checkinRepository;
    @Mock private CbtReconstructionRepository cbtReconstructionRepository;
    @Mock private SessionSummaryRepository sessionSummaryRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private BehaviorTaskRepository behaviorTaskRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReportNarrativeService reportNarrativeService;

    private ReportService reportService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        reportService = new ReportService(
                checkinRepository, cbtReconstructionRepository, sessionSummaryRepository,
                sessionRepository, behaviorTaskRepository, userRepository,
                reportNarrativeService, directTransactionManager());
        reportService.init();

        when(userRepository.existsById(userId)).thenReturn(true);
        when(checkinRepository.countByUser_IdAndCheckinDateBetween(any(), any(), any())).thenReturn(3L);
        when(sessionSummaryRepository.findBiasTypeDistribution(any(), any(), any())).thenReturn(List.of());
        when(behaviorTaskRepository.findTodoStatsByUserAndPeriod(any(), any(), any())).thenReturn(List.of());
        when(sessionRepository.findEndedSessionsByUserAndPeriod(any(), any(), any())).thenReturn(List.of());
        when(reportNarrativeService.generate(any(), anyInt(), any(), anyList(), any()))
                .thenReturn(ReportNarrativeService.NarrativeResult.empty());
    }

    @Test
    @DisplayName("CBT 사용자 입력 감정 점수 평균을 avg_emotion_score로 반환한다")
    void getWeeklyReport_usesCbtEmotionScoreAfterAverage() {
        when(cbtReconstructionRepository.findAvgEmotionScoreAfter(eq(userId), any(), any()))
                .thenReturn(62.5);

        WeeklyReportResponse response = reportService.getWeeklyReport(userId, LocalDate.of(2026, 8, 10));

        assertThat(response.avgEmotionScore()).isEqualTo(62.5);
        verify(cbtReconstructionRepository).findAvgEmotionScoreAfter(eq(userId), any(OffsetDateTime.class), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("그 주에 CBT 감정 점수 제출이 없으면 avg_emotion_score는 null이다")
    void getWeeklyReport_withoutCbtScores_returnsNullAverage() {
        when(cbtReconstructionRepository.findAvgEmotionScoreAfter(eq(userId), any(), any()))
                .thenReturn(null);

        WeeklyReportResponse response = reportService.getWeeklyReport(userId, LocalDate.of(2026, 8, 10));

        assertThat(response.avgEmotionScore()).isNull();
    }

    private static PlatformTransactionManager directTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // no-op
            }

            @Override
            public void rollback(TransactionStatus status) {
                // no-op
            }
        };
    }
}
