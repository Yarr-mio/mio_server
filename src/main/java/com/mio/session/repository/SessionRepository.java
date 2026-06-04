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

    @Query("""
            SELECT s FROM Session s
            WHERE s.status = com.mio.session.domain.SessionStatus.ACTIVE
              AND (
                (s.lastMessageAt IS NOT NULL AND s.lastMessageAt <= :cutoff)
                OR (s.lastMessageAt IS NULL AND s.startedAt <= :cutoff)
              )
            """)
    List<Session> findTimedOutActiveSessions(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * 원자적 상태 전이: ACTIVE → ENDED (조건부 UPDATE).
     * 동시 실행 환경에서 단일 인스턴스만 처리하도록 보장.
     * 반환값 1 = 성공(해당 인스턴스가 처리), 0 = 이미 종료됨(skip).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Session s
            SET s.status = com.mio.session.domain.SessionStatus.ENDED, s.endedAt = :endedAt
            WHERE s.id = :sessionId
              AND s.status = com.mio.session.domain.SessionStatus.ACTIVE
              AND (
                (s.lastMessageAt IS NOT NULL AND s.lastMessageAt <= :cutoff)
                OR (s.lastMessageAt IS NULL AND s.startedAt <= :cutoff)
              )
            """)
    int endSessionIfActive(@Param("sessionId") UUID sessionId,
                           @Param("cutoff") OffsetDateTime cutoff,
                           @Param("endedAt") OffsetDateTime endedAt);

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
