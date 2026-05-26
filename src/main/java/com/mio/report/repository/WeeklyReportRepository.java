package com.mio.report.repository;

import com.mio.report.domain.WeeklyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface WeeklyReportRepository extends JpaRepository<WeeklyReport, UUID> {

    Optional<WeeklyReport> findByUser_IdAndWeekStart(UUID userId, LocalDate weekStart);
}
