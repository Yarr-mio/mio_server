package com.mio.report.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 주간 리포트의 대상 주차를 계산한다 (이슈 #415).
 *
 * <p>같은 "지난주 월요일"을 집계 job · 알림 발송 게이트 · 리포트 조회가 각자 계산하고 있었다.
 * 그중 집계 job 만 {@code 오늘 - 1일 - 6일} 이라 <b>월요일에 실행될 때만</b> 옳았고, 다른 요일에
 * 재실행하면 월요일이 아닌 {@code week_start} 로 저장돼 나머지 두 곳의 조회와 어긋났다.
 * 그러면 그 주 리포트 알림이 전 유저 무발송이 되면서도 리포트 화면에는 정상 표시되는,
 * 원인을 찾기 어려운 불일치가 생긴다.
 *
 * <p>결합을 주석이 아니라 코드로 표현하기 위해 한곳에 모았다.
 *
 * <p><b>아직 이 헬퍼를 쓰지 않는 곳이 하나 있다.</b>
 * {@code WeeklyReflectionJob} 은 일요일 00:00 에 {@code 오늘 - 7일}(= 일요일)로 {@code week_start}
 * 를 만들어 {@code weekly_reports} 를 UPDATE 한다. {@code week_start} 에는 월요일만 저장되므로
 * 이 UPDATE 는 항상 0 rows 이며, {@code narrative}/{@code coaching_direction} 이 채워지지 않는다
 * (프로덕션 확인: 9행 중 0행). 이 헬퍼 도입 <b>이전부터</b> 그랬고, 고치려면 날짜 계산만이 아니라
 * 두 job 의 실행 순서(일 00:00 반영 → 월 03:00 집계)부터 정해야 해서 별도로 다룬다.
 */
public final class ReportWeek {

    private ReportWeek() {
    }

    /**
     * 기준일이 속한 주의 <b>직전 주 월요일</b>. 실행 요일과 무관하게 항상 같은 값을 낸다.
     *
     * <p>ISO-8601 기준으로 주는 월요일에 시작한다.
     */
    public static LocalDate lastWeekStartFrom(LocalDate baseDate) {
        return baseDate.with(DayOfWeek.MONDAY).minusWeeks(1);
    }

    /** 주 시작일(월)에 대응하는 종료일(일). */
    public static LocalDate weekEndOf(LocalDate weekStart) {
        return weekStart.plusDays(6);
    }
}
