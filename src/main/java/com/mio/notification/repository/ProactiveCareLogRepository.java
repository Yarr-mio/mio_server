package com.mio.notification.repository;

import com.mio.notification.domain.ProactiveCareLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProactiveCareLogRepository extends JpaRepository<ProactiveCareLog, UUID> {

    Optional<ProactiveCareLog> findByIdAndUser_Id(UUID id, UUID userId);

    boolean existsByUser_IdAndTriggerCodeAndRespondedAtIsNullAndSentAtAfter(
            UUID userId,
            String triggerCode,
            OffsetDateTime sentAt
    );

    long countByUser_IdAndSentAtBetween(UUID userId, OffsetDateTime from, OffsetDateTime to);

    List<ProactiveCareLog> findByUser_IdOrderBySentAtDesc(UUID userId, Pageable pageable);

    List<ProactiveCareLog> findByUser_IdAndSentAtLessThanOrderBySentAtDesc(
            UUID userId,
            OffsetDateTime sentAt,
            Pageable pageable
    );
}
