package com.mio.todo.repository;

import com.mio.todo.domain.BehaviorTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface BehaviorTaskRepository extends JpaRepository<BehaviorTask, UUID> {

    List<BehaviorTask> findByUser_IdAndCreatedAtBetween(
            UUID userId, OffsetDateTime from, OffsetDateTime to
    );

    List<BehaviorTask> findByUser_IdAndStatusAndCreatedAtBetween(
            UUID userId, String status, OffsetDateTime from, OffsetDateTime to
    );
}
