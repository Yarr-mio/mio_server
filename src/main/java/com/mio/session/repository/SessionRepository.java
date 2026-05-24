package com.mio.session.repository;

import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByUser_IdAndStatus(UUID userId, SessionStatus status);

    boolean existsByUser_IdAndStatus(UUID userId, SessionStatus status);
}
