package com.mio.session.repository;

import com.mio.session.domain.SessionCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionCheckpointRepository extends JpaRepository<SessionCheckpoint, UUID> {

    List<SessionCheckpoint> findBySession_IdOrderByCheckpointSeqAsc(UUID sessionId);

    Optional<SessionCheckpoint> findTopBySession_IdOrderByCheckpointSeqDesc(UUID sessionId);

    int countBySession_Id(UUID sessionId);
}
