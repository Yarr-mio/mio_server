package com.mio.ai.crisis;

import com.mio.crisis.domain.CrisisEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrisisEventRepository extends JpaRepository<CrisisEvent, UUID> {

    /** 재시도가 같은 논리 이벤트를 다시 만들지 않도록 기존 행을 찾는다 (이슈 #269). */
    Optional<CrisisEvent> findByDedupKey(UUID dedupKey);

    List<CrisisEvent> findBySession_IdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * 원자적 조건부 검토 처리 (이슈 #277 리뷰 반영).
     *
     * <p>read-then-write(findById → isOperatorReviewed 확인 → review())로 하면 동시 요청
     * 둘 다 검사를 통과해 서로 덮어쓸 수 있다. WHERE 절에 operator_reviewed = false 를 걸어
     * DB 가 동시성을 직렬화하게 한다 — SessionRepository.endSessionIfActive 와 동일 패턴.
     *
     * @return 1 = 이번 호출이 검토 처리함, 0 = 이미 다른 요청이 먼저 처리함(호출자가 409 판단)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CrisisEvent c
            SET c.operatorReviewed = true, c.reviewAction = :action, c.operatorNote = :note
            WHERE c.id = :eventId AND c.operatorReviewed = false
            """)
    int reviewIfNotAlready(@Param("eventId") UUID eventId,
                           @Param("action") String action,
                           @Param("note") String note);
}
