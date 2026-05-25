package com.mio.checkin.repository;

import com.mio.checkin.domain.Checkin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface CheckinRepository extends JpaRepository<Checkin, UUID> {

    Optional<Checkin> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<Checkin> findTopByUser_IdAndCheckinDateOrderByCreatedAtDesc(UUID userId, LocalDate date);

    Optional<Checkin> findTopByUser_IdOrderByCreatedAtDesc(UUID userId);
}
