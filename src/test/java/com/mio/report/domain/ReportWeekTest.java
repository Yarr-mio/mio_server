package com.mio.report.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReportWeek — 주간 리포트 대상 주차 계산 (#415)")
class ReportWeekTest {

    /**
     * 이 계산이 세 곳(집계 job · 알림 게이트 · 리포트 조회)에서 각자 이뤄지던 것을 하나로 모았다.
     * 실행 요일에 따라 값이 달라지면 저장한 주차와 조회하는 주차가 어긋나 알림이 통째로 사라진다.
     */
    @ParameterizedTest(name = "{0}({1}) 에 실행해도 대상 주차는 2026-08-03")
    @CsvSource({
            "2026-08-10, 월요일",
            "2026-08-11, 화요일",
            "2026-08-12, 수요일",
            "2026-08-13, 목요일",
            "2026-08-14, 금요일",
            "2026-08-15, 토요일",
            "2026-08-16, 일요일"
    })
    @DisplayName("같은 주 안에서는 어느 요일에 실행해도 같은 주차를 가리킨다")
    void lastWeekStartFrom_isIndependentOfExecutionDay(LocalDate baseDate, String label) {
        assertThat(ReportWeek.lastWeekStartFrom(baseDate))
                .as("%s 실행", label)
                .isEqualTo(LocalDate.of(2026, 8, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-08-10", "2026-08-11", "2026-08-16",
            "2026-01-01", "2026-03-02", "2027-01-04"
    })
    @DisplayName("결과는 항상 월요일이다")
    void lastWeekStartFrom_alwaysReturnsMonday(String baseDate) {
        assertThat(ReportWeek.lastWeekStartFrom(LocalDate.parse(baseDate)).getDayOfWeek())
                .isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("연말연시 주 경계에서도 직전 주 월요일을 가리킨다")
    void lastWeekStartFrom_crossesYearBoundary() {
        // 2026-01-01 은 목요일 → 그 주 월요일은 2025-12-29 → 직전 주는 2025-12-22
        assertThat(ReportWeek.lastWeekStartFrom(LocalDate.of(2026, 1, 1)))
                .isEqualTo(LocalDate.of(2025, 12, 22));
    }

    @Test
    @DisplayName("주 종료일은 시작일로부터 6일 뒤 일요일이다")
    void weekEndOf_returnsSunday() {
        LocalDate weekStart = LocalDate.of(2026, 8, 3);

        assertThat(ReportWeek.weekEndOf(weekStart)).isEqualTo(LocalDate.of(2026, 8, 9));
        assertThat(ReportWeek.weekEndOf(weekStart).getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
    }

    /**
     * 교체 대상이던 기존 계산식과 동일한 값을 내는지 고정한다.
     *
     * <p>{@code ReportService#resolveLastWeekStart} 와 월요일 실행 시의
     * {@code ReportAggregationJob} 은 원래도 옳았다. 이 헬퍼가 그 동작을 바꾸면 안 된다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2026-08-10", "2026-08-11", "2026-08-16", "2026-02-28", "2028-02-29"})
    @DisplayName("기존 ReportService 계산식과 결과가 같다")
    void lastWeekStartFrom_matchesLegacyReportServiceFormula(String baseDate) {
        LocalDate today = LocalDate.parse(baseDate);
        int daysFromMonday = today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        LocalDate legacy = today.minusDays(daysFromMonday + 7L);

        assertThat(ReportWeek.lastWeekStartFrom(today)).isEqualTo(legacy);
    }

    @Test
    @DisplayName("월요일 실행 시 기존 집계 job 계산식과 결과가 같다")
    void lastWeekStartFrom_matchesLegacyJobFormulaOnMonday() {
        LocalDate monday = LocalDate.of(2026, 8, 10);
        LocalDate legacyWeekEnd = monday.minusDays(1);
        LocalDate legacyWeekStart = legacyWeekEnd.minusDays(6);

        assertThat(ReportWeek.lastWeekStartFrom(monday)).isEqualTo(legacyWeekStart);
        assertThat(ReportWeek.weekEndOf(ReportWeek.lastWeekStartFrom(monday))).isEqualTo(legacyWeekEnd);
    }
}
