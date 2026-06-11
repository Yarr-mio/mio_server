package com.mio.session.repository;

import com.mio.session.domain.SessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SessionSummaryRepository extends JpaRepository<SessionSummary, UUID> {
    Optional<SessionSummary> findBySession_Id(UUID sessionId);
}
