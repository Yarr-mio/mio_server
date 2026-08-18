package com.mio.session.repository;

import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import com.mio.session.domain.SummaryStatus;
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

    Optional<Session> findTopByUser_IdAndStatusOrderByEndedAtDesc(UUID userId, SessionStatus status);

    boolean existsByUser_IdAndStatus(UUID userId, SessionStatus status);

    boolean existsByIdAndStatus(UUID id, SessionStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Session s
            SET s.summaryStatus = :status,
                s.summaryProcessingStartedAt = NULL
            WHERE s.id = :sessionId
            """)
    void updateSummaryStatus(@Param("sessionId") UUID sessionId, @Param("status") SummaryStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Session s
            SET s.summaryProcessingStartedAt = :startedAt
            WHERE s.id = :sessionId
              AND s.summaryStatus = com.mio.session.domain.SummaryStatus.PENDING
            """)
    int markSummaryProcessingStarted(@Param("sessionId") UUID sessionId,
                                     @Param("startedAt") OffsetDateTime startedAt);

    /**
     * 유예 시간이 지나도 pending 이지만 <b>결과물은 이미 갖춘</b> 종료 세션을 완료로 회복시킨다
     * (이슈 #356).
     *
     * <p>컨솔리데이션은 요약을 독립 트랜잭션으로 먼저 커밋한다. 그 직후 배포·크래시로
     * 프로세스가 죽으면 완성된 요약을 두고도 pending 이 남는다. Todo는 선택 작업이므로
     * 존재 여부와 무관하게 핵심 요약을 회복한다.
     *
     * @return 완료로 회복된 세션 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Session s
            SET s.summaryStatus = com.mio.session.domain.SummaryStatus.DONE,
                s.summaryProcessingStartedAt = NULL
            WHERE s.status = com.mio.session.domain.SessionStatus.ENDED
              AND s.summaryStatus = com.mio.session.domain.SummaryStatus.PENDING
              AND s.endedAt IS NOT NULL
              AND COALESCE(s.summaryProcessingStartedAt, s.endedAt) <= :cutoff
              AND EXISTS (SELECT 1 FROM SessionSummary ss WHERE ss.session = s)
            """)
    int recoverStalePendingSummaries(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * 유예 시간이 지나도 pending 인 종료 세션의 요약을 실패로 확정한다 (이슈 #356).
     *
     * <p>컨솔리데이션은 종료 트랜잭션 커밋 후 비동기로 돌기 때문에, 그 사이 서버가 재시작되면
     * 상태가 pending 에 영구히 남는다. 요약 조회는 pending 을 202 로 응답하므로 클라이언트는
     * 무한 로딩에 갇힌다. 되살릴 방법이 없는 상태를 종결시켜 사용자가 빠져나올 수 있게 한다.
     *
     * <p>반드시 {@link #recoverStalePendingSummaries} 뒤에 실행해야 한다. 회복 가능한 세션을
     * 먼저 건져낸 뒤 남은 것만 종결시킨다.
     *
     * <p>WHERE 절이 실행 시점에 pending 을 다시 확인하므로, 뒤늦게 완료된 컨솔리데이션의
     * done 을 덮어쓰지 않는다.
     *
     * @return 실패로 전환된 세션 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Session s
            SET s.summaryStatus = com.mio.session.domain.SummaryStatus.FAILED,
                s.summaryProcessingStartedAt = NULL
            WHERE s.status = com.mio.session.domain.SessionStatus.ENDED
              AND s.summaryStatus = com.mio.session.domain.SummaryStatus.PENDING
              AND s.endedAt IS NOT NULL
              AND COALESCE(s.summaryProcessingStartedAt, s.endedAt) <= :cutoff
              AND NOT EXISTS (SELECT 1 FROM SessionSummary ss WHERE ss.session = s)
            """)
    int markStalePendingSummariesFailed(@Param("cutoff") OffsetDateTime cutoff);

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Session s
            SET s.cbtCompletionReason = :reason
            WHERE s.id = :sessionId
            """)
    void updateCbtCompletionReason(@Param("sessionId") UUID sessionId, @Param("reason") String reason);

    /**
     * 리텐션 반응신호(이슈 #476) — {@code after}(보통 세션 endedAt) 뒤,
     * {@code beforeOrEqual}(보통 endedAt + 7일) 안에 이 유저의 다른 세션이 시작됐는지.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM Session s
            WHERE s.user.id = :userId AND s.startedAt > :after AND s.startedAt <= :beforeOrEqual
            """)
    boolean existsSessionStartedInWindow(@Param("userId") UUID userId,
                                         @Param("after") OffsetDateTime after,
                                         @Param("beforeOrEqual") OffsetDateTime beforeOrEqual);

    /**
     * 사용자의 모든 세션 ID (이슈 #373).
     *
     * <p>Redis 캐시 키가 {@code session:{sessionId}:*} 라 사용자 단위 purge 를 하려면
     * 세션 ID 목록이 필요하다. 엔티티 전체를 읽으면 삭제 한 번에 세션 수만큼의 행을
     * 메모리에 올리게 되므로 ID 만 가져온다.
     */
    @Query("SELECT s.id FROM Session s WHERE s.user.id = :userId")
    List<UUID> findAllIdsByUserId(@Param("userId") UUID userId);
}
