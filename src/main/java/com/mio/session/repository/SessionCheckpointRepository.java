package com.mio.session.repository;

import com.mio.session.domain.SessionCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionCheckpointRepository extends JpaRepository<SessionCheckpoint, UUID> {

    List<SessionCheckpoint> findBySession_IdOrderByCheckpointSeqAsc(UUID sessionId);

    @Query("SELECT c FROM SessionCheckpoint c WHERE c.session.id = :sessionId ORDER BY c.checkpointSeq DESC LIMIT 1")
    Optional<SessionCheckpoint> findLatestBySessionId(@Param("sessionId") UUID sessionId);

    int countBySession_Id(UUID sessionId);
}
