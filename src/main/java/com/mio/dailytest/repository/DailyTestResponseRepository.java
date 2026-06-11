package com.mio.dailytest.repository;

import com.mio.dailytest.domain.DailyTestResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DailyTestResponseRepository extends JpaRepository<DailyTestResponse, UUID> {

    Optional<DailyTestResponse> findByUser_IdAndDailyTest_Id(UUID userId, UUID dailyTestId);
}
