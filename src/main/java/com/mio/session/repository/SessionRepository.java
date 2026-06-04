package com.mio.session.repository;

import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    @Query("SELECT s FROM Session s WHERE s.status = com.mio.session.domain.SessionStatus.ACTIVE AND s.lastMessageAt < :cutoff")
    List<Session> findTimedOutActiveSessions(@Param("cutoff") OffsetDateTime cutoff);

    Optional<Session> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<Session> findByUser_IdAndStatus(UUID userId, SessionStatus status);

    boolean existsByUser_IdAndStatus(UUID userId, SessionStatus status);

    @Query("SELECT s FROM Session s WHERE s.user.id = :userId AND s.startedAt >= :start AND s.startedAt < :end AND s.status = com.mio.session.domain.SessionStatus.ENDED AND s.endedAt IS NOT NULL")
    List<Session> findEndedSessionsByUserAndPeriod(@Param("userId") UUID userId,
                                                   @Param("start") OffsetDateTime start,
                                                   @Param("end") OffsetDateTime end);

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
