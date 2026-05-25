package com.mio.todo.repository;

import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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

    boolean existsByUser_IdAndGeneratedFromAndSourceCheckin_IdAndStatusAndCreatedAtBetween(
            UUID userId,
            String generatedFrom,
            UUID sourceCheckinId,
            TaskStatus status,
            OffsetDateTime from,
            OffsetDateTime to
    );

    boolean existsByUser_IdAndGeneratedFromAndSourceSession_IdAndStatusAndCreatedAtBetween(
            UUID userId,
            String generatedFrom,
            UUID sourceSessionId,
            TaskStatus status,
            OffsetDateTime from,
            OffsetDateTime to
    );

    boolean existsByUser_IdAndGeneratedFromAndSourceCheckinIsNullAndSourceSessionIsNullAndStatusAndCreatedAtBetween(
            UUID userId,
            String generatedFrom,
            TaskStatus status,
            OffsetDateTime from,
            OffsetDateTime to
    );
}
