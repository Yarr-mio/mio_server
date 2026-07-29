package com.mio.session.repository;

import com.mio.session.domain.MessageTurn;
import com.mio.session.domain.TurnStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageTurnRepository extends JpaRepository<MessageTurn, UUID> {

    /** 같은 Idempotency-Key 재시도 시 기존 턴을 찾는다. */
    Optional<MessageTurn> findByUser_IdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /**
     * 응답을 만들지 못한 채 오래 남은 턴. 프로세스가 죽어 터미널 상태를 못 남긴 경우다.
     *
     * <p>재시도 판단(진행 중인가, 버려진 것인가)과 사후 회수에 쓴다.
     */
    List<MessageTurn> findByStatusAndUpdatedAtBefore(TurnStatus status, OffsetDateTime before);

    /**
     * 진행 중임을 알린다 — 리스를 쥐고 있을 때만 {@code updated_at} 을 민다.
     *
     * <p>엔티티를 거치지 않는 조건부 UPDATE 다. 다른 시도가 이어받았다면 0을 반환하고 아무것도
     * 바꾸지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update MessageTurn t
               set t.updatedAt = :now
             where t.id = :turnId
               and t.leaseToken = :leaseToken
               and t.status = com.mio.session.domain.TurnStatus.GENERATING
            """)
    int touchIfHeld(@Param("turnId") UUID turnId,
                    @Param("leaseToken") UUID leaseToken,
                    @Param("now") OffsetDateTime now);
}
