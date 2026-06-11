package com.mio.ai.memory.episodic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface UserBeliefRepository extends JpaRepository<UserBelief, UUID> {

    @Query("""
            SELECT b FROM UserBelief b
            WHERE b.user.id = :userId AND b.status = 'active'
            ORDER BY b.confidence DESC
            LIMIT 5
            """)
    List<UserBelief> findActiveTop5(UUID userId);

    List<UserBelief> findByUser_IdAndStatus(UUID userId, String status);
}
