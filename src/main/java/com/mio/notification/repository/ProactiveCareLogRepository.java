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

    /**
     * 알림 이력 첫 페이지. {@code excludedStatuses}(= {@link ProactiveCareLog#INTERNAL_ONLY_STATUSES})
     * 에 해당하는 이력은 <b>쿼리에서</b> 걸러낸다 (이슈 #397).
     *
     * <p>애플리케이션 레이어에서 거르면 안 된다. 호출부는 {@code pageSize + 1} 건을 읽어
     * {@code hasMore} 를 판정하는데, 읽어온 뒤에 거르면 페이지가 요청 크기보다 작아지거나
     * ("60건 중 안 보이는 게 55건"이면 첫 페이지가 통째로 빈다) {@code hasMore} 가 틀어진다.
     */
    @Query("""
            SELECT log
            FROM ProactiveCareLog log
            WHERE log.user.id = :userId
              AND log.notificationStatus NOT IN :excludedStatuses
            ORDER BY log.sentAt DESC, log.id DESC
            """)
    List<ProactiveCareLog> findVisiblePageByUserId(
            @Param("userId") UUID userId,
            @Param("excludedStatuses") Collection<String> excludedStatuses,
            Pageable pageable
    );

    /** 알림 이력 다음 페이지. 제외 기준은 {@link #findVisiblePageByUserId} 와 같다. */
    @Query("""
            SELECT log
            FROM ProactiveCareLog log
            WHERE log.user.id = :userId
              AND log.notificationStatus NOT IN :excludedStatuses
              AND (
                    log.sentAt < :sentAt
                    OR (log.sentAt = :sentAt AND log.id < :cursorId)
              )
            ORDER BY log.sentAt DESC, log.id DESC
            """)
    List<ProactiveCareLog> findVisiblePageByUserIdAfterCursor(
            @Param("userId") UUID userId,
            @Param("excludedStatuses") Collection<String> excludedStatuses,
            @Param("sentAt") OffsetDateTime sentAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );
}
