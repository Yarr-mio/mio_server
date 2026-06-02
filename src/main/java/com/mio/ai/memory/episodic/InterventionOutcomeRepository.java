package com.mio.ai.memory.episodic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface InterventionOutcomeRepository extends JpaRepository<InterventionOutcome, UUID> {

    @Query("""
            SELECT io FROM InterventionOutcome io
            WHERE io.user.id = :userId
            ORDER BY io.createdAt DESC
            LIMIT 20
            """)
    List<InterventionOutcome> findRecentByUserId(UUID userId);
}
