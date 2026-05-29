package com.mio.report.repository;

import com.mio.report.domain.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, UUID> {

    Optional<WeeklyReport> findByUser_IdAndWeekStart(UUID userId, LocalDate weekStart);

    // week_start 생략 시 가장 최근 주 반환용
    Optional<WeeklyReport> findTop1ByUser_IdOrderByWeekStartDesc(UUID userId);

    List<WeeklyReport> findByUser_IdOrderByWeekStartDesc(UUID userId);
}
