package com.mio.checkin.repository;

import com.mio.checkin.domain.Checkin;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckinRepository extends JpaRepository<Checkin, UUID> {

    Optional<Checkin> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<Checkin> findTopByUser_IdAndCheckinDateOrderByCreatedAtDesc(UUID userId, LocalDate date);

    Optional<Checkin> findTopByUser_IdOrderByCreatedAtDesc(UUID userId);

    boolean existsByUser_IdAndCheckinDateAndTimeOfDay(UUID userId, LocalDate checkinDate, String timeOfDay);

    List<Checkin> findTop3ByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<Checkin> findByUser_IdAndCheckinDate(UUID userId, LocalDate checkinDate);

    @Query("SELECT c FROM Checkin c WHERE c.user.id = :userId AND c.createdAt < :cursor ORDER BY c.createdAt DESC")
    Slice<Checkin> findByUser_IdAndCreatedAtBefore(@Param("userId") UUID userId,
                                                    @Param("cursor") OffsetDateTime cursor,
                                                    Pageable pageable);

    @Query("SELECT c FROM Checkin c WHERE c.user.id = :userId ORDER BY c.createdAt DESC")
    Slice<Checkin> findByUser_IdOrderByCreatedAtDesc(@Param("userId") UUID userId, Pageable pageable);
}
