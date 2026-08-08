package com.mio.notification.repository;

import com.mio.notification.domain.ProactiveCareLog;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProactiveCareLogRepository extends JpaRepository<ProactiveCareLog, UUID> {

    Optional<ProactiveCareLog> findByIdAndUser_Id(UUID id, UUID userId);

    /**
     * 실제로 발송된 상태({@code DELIVERED_STATUSES})의 이력만 재발송 억제 대상으로 본다.
     * 실패·미발송 이력은 재시도를 막지 않는다.
     */
    boolean existsByUser_IdAndTriggerCodeAndNotificationStatusInAndSentAtAfter(
            UUID userId,
            String triggerCode,
            Collection<String> notificationStatuses,
            OffsetDateTime sentAt
    );

    /** 실제로 발송된 이력만 일일 한도에 반영한다. */
    long countByUser_IdAndNotificationStatusInAndSentAtBetween(
            UUID userId,
            Collection<String> notificationStatuses,
            OffsetDateTime from,
            OffsetDateTime to
    );

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
