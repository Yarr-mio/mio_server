package com.mio.dailytest.repository;

import com.mio.dailytest.domain.DailyTest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DailyTestRepository extends JpaRepository<DailyTest, UUID> {

    Optional<DailyTest> findByActiveDate(LocalDate date);
}
