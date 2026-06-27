package com.mio.todo.repository;

import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;
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

    // 리포트용: status·category별 집계
    @Query("SELECT t.status, t.category, COUNT(t) FROM BehaviorTask t WHERE t.user.id = :userId AND t.createdAt >= :start AND t.createdAt < :end GROUP BY t.status, t.category")
    List<Object[]> findTodoStatsByUserAndPeriod(@Param("userId") UUID userId,
                                                @Param("start") OffsetDateTime start,
                                                @Param("end") OffsetDateTime end);
    long countByUser_IdAndStatus(UUID userId, TaskStatus status);

    long countByUser_IdAndInterventionKindAndStatus(UUID userId, String interventionKind, TaskStatus status);
}
