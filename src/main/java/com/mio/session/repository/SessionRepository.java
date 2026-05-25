package com.mio.session.repository;

import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<Session> findByUser_IdAndStatus(UUID userId, SessionStatus status);

    boolean existsByUser_IdAndStatus(UUID userId, SessionStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Session s
            set s.messageCount = s.messageCount + :increment,
                s.lastMessageAt = :lastMessageAt
            where s.id = :sessionId
            """)
    int incrementMessageCountAndSetLastMessageAt(
            @Param("sessionId") UUID sessionId,
            @Param("increment") int increment,
            @Param("lastMessageAt") OffsetDateTime lastMessageAt
    );
}
