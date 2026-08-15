package com.mio.user.repository;

import com.mio.user.domain.DataDeletionRequest;
import com.mio.user.domain.DeletionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataDeletionRequestRepository extends JpaRepository<DataDeletionRequest, UUID> {

    /** 멀티 인스턴스 배치가 같은 작업을 동시에 실행하지 못하도록 행을 선점한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DataDeletionRequest r where r.id = :id")
    Optional<DataDeletionRequest> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 사용자의 가장 최근 삭제 요청. 상태 조회 API 가 쓴다.
     *
     * <p>완료된 요청도 돌려준다 — "삭제가 끝났다" 도 상태다. 진행 중인 것만 찾으면 완료
     * 직후 조회가 "요청 없음" 으로 보인다.
     */
    Optional<DataDeletionRequest> findTopByUserIdOrderByRequestedAtDesc(UUID userId);

    /** 진행 중인 요청. 탈퇴를 두 번 눌렀을 때 요청을 새로 만들지 않기 위해 쓴다. */
    @Query("""
            select r from DataDeletionRequest r
             where r.userId = :userId
               and r.status in (com.mio.user.domain.DeletionStatus.PENDING,
                                com.mio.user.domain.DeletionStatus.IN_PROGRESS)
            """)
    Optional<DataDeletionRequest> findActiveByUserId(@Param("userId") UUID userId);

    /**
     * 유예 기간이 지나 하드 삭제할 수 있는 요청.
     *
     * <p>{@code scheduled_at} 부분 인덱스를 탄다. 상한을 두는 이유는 한 번의 실행이 전체를
     * 붙잡지 않게 하기 위해서다 — 남은 것은 다음 실행이 가져간다.
     */
    @Query(value = """
            SELECT r.*
              FROM data_deletion_requests r
             WHERE r.status = 'pending'
               AND r.scheduled_at <= :now
               AND (
                    CAST(:afterScheduledAt AS timestamptz) IS NULL
                    OR (r.scheduled_at, r.id) >
                       (CAST(:afterScheduledAt AS timestamptz), CAST(:afterId AS uuid))
               )
             ORDER BY r.scheduled_at ASC, r.id ASC
            """, nativeQuery = true)
    List<DataDeletionRequest> findDueAfter(
            @Param("now") OffsetDateTime now,
            @Param("afterScheduledAt") OffsetDateTime afterScheduledAt,
            @Param("afterId") UUID afterId,
            org.springframework.data.domain.Pageable pageable);

    /** 유예 기간이 끝났지만 아직 terminal state에 도달하지 못한 운영 backlog. */
    @Query("""
            select count(r) from DataDeletionRequest r
             where r.status = com.mio.user.domain.DeletionStatus.PENDING
               and r.scheduledAt <= :now
            """)
    long countDue(@Param("now") OffsetDateTime now);

    long countByStatus(DeletionStatus status);
}
