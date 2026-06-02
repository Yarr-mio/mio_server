package com.mio.ai.memory.episodic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ThoughtRepository extends JpaRepository<Thought, UUID> {
    List<Thought> findByUser_IdAndSessionId(UUID userId, UUID sessionId);
}
