package com.mio.notification.repository;

import com.mio.notification.domain.ProactiveCareLog;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProactiveCareLogRepository extends JpaRepository<ProactiveCareLog, UUID> {

    Optional<ProactiveCareLog> findByIdAndUser_Id(UUID id, UUID userId);

    boolean existsByUser_IdAndTriggerCodeAndSentAtAfter(
            UUID userId,
            String triggerCode,
            OffsetDateTime sentAt
    );

    long countByUser_IdAndSentAtBetween(UUID userId, OffsetDateTime from, OffsetDateTime to);

    @Query("""
            SELECT log
            FROM ProactiveCareLog log
            WHERE log.user.id = :userId
            ORDER BY log.sentAt DESC, log.id DESC
            """)
    List<ProactiveCareLog> findPageByUserId(
            @Param("userId") UUID userId,
            Pageable pageable
    );

    @Query("""
            SELECT log
            FROM ProactiveCareLog log
            WHERE log.user.id = :userId
              AND (
                    log.sentAt < :sentAt
                    OR (log.sentAt = :sentAt AND log.id < :cursorId)
              )
            ORDER BY log.sentAt DESC, log.id DESC
            """)
    List<ProactiveCareLog> findPageByUserIdAfterCursor(
            @Param("userId") UUID userId,
            @Param("sentAt") OffsetDateTime sentAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );
}
