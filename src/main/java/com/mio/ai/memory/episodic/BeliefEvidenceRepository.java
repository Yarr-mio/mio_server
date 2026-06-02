package com.mio.ai.memory.episodic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BeliefEvidenceRepository extends JpaRepository<BeliefEvidence, UUID> {
    List<BeliefEvidence> findByBelief_Id(UUID beliefId);
}
