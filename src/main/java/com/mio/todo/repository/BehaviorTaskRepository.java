package com.mio.todo.repository;

import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface BehaviorTaskRepository extends JpaRepository<BehaviorTask, UUID> {

    List<BehaviorTask> findByUser_IdAndCreatedAtBetween(
            UUID userId, OffsetDateTime from, OffsetDateTime to
    );

    List<BehaviorTask> findByUser_IdAndStatusAndCreatedAtBetween(
            UUID userId, TaskStatus status, OffsetDateTime from, OffsetDateTime to
    );

    List<BehaviorTask> findBySourceSession_Id(UUID sourceSessionId);

    boolean existsByUser_IdAndGeneratedFromAndSourceSession_IdAndStatusAndCreatedAtBetween(
            UUID userId,
            String generatedFrom,
            UUID sourceSessionId,
            TaskStatus status,
            OffsetDateTime from,
            OffsetDateTime to
    );

    /**
     * 최근 발급 감점(이슈 #337)용 — 세션에서 생성된 Todo 의 출처 템플릿을 최신순으로 조회한다.
     *
     * <p>세션 거리로 감점을 매기므로 {@code sourceSession} 이 없는 생성 경로(체크인 등)와
     * 템플릿을 거치지 않은 과거 row 는 제외한다. 조회 건수는 호출측이 {@link Pageable} 로 제한한다.
     */
    @Query("""
            SELECT t.templateCode AS templateCode, t.sourceSession.id AS sessionId
            FROM BehaviorTask t
            WHERE t.user.id = :userId
              AND t.templateCode IS NOT NULL
              AND t.sourceSession IS NOT NULL
            ORDER BY t.createdAt DESC
            """)
    List<RecentTemplateRow> findRecentSessionTemplates(@Param("userId") UUID userId, Pageable pageable);

    /** {@link #findRecentSessionTemplates} 투영. */
    interface RecentTemplateRow {
        String getTemplateCode();

        UUID getSessionId();
    }

    // 리포트용: status·category별 집계
    @Query("SELECT t.status, t.category, COUNT(t) FROM BehaviorTask t WHERE t.user.id = :userId AND t.createdAt >= :start AND t.createdAt < :end GROUP BY t.status, t.category")
    List<Object[]> findTodoStatsByUserAndPeriod(@Param("userId") UUID userId,
                                                @Param("start") OffsetDateTime start,
                                                @Param("end") OffsetDateTime end);
    long countByUser_IdAndStatus(UUID userId, TaskStatus status);

    long countByUser_IdAndInterventionKindAndStatus(UUID userId, String interventionKind, TaskStatus status);
}
