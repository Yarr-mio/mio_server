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

    /**
     * 같은 세션에서 같은 Idempotency-Key 로 재시도한 턴을 찾는다.
     *
     * <p>사용자가 아니라 <b>세션</b>으로 범위를 잡는다. Idempotency-Key 는 엔드포인트 호출
     * 단위이고 이 엔드포인트는 세션에 속한다. 사용자 단위로 잡으면 다른 세션에서 같은 키를
     * 재사용했을 때 이전 세션의 턴을 재개하고, 생성된 응답이 그 세션에 저장된다.
     */
    Optional<MessageTurn> findBySession_IdAndIdempotencyKey(UUID sessionId, String idempotencyKey);

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

    /**
     * 리스를 쥐고 있을 때만 터미널 상태로 전이한다 — 소유권 확인과 전이가 한 번의 원자 연산이다.
     *
     * <p>{@code findById} 로 읽고 메모리에서 토큰을 비교한 뒤 저장하는 방식은 compare-and-set 이
     * 아니다. 그 사이에 재시도가 리스를 가져가고 커밋해도, 이미 읽어둔 엔티티를 저장하면 새
     * 리스를 덮어쓴다. {@code WHERE lease_token = ?} 조건을 DB 로 내려 그 창을 없앤다.
     *
     * @return 1이면 전이 성공, 0이면 다른 시도가 이어받았다는 뜻
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update MessageTurn t
               set t.status = :status,
                   t.finishedReason = :finishedReason,
                   t.assistantMessageId = :assistantMessageId,
                   t.crisisSeverity = :crisisSeverity,
                   t.updatedAt = :now
             where t.id = :turnId
               and t.leaseToken = :leaseToken
               and t.status = com.mio.session.domain.TurnStatus.GENERATING
            """)
    int finishIfHeld(@Param("turnId") UUID turnId,
                     @Param("leaseToken") UUID leaseToken,
                     @Param("status") TurnStatus status,
                     @Param("finishedReason") String finishedReason,
                     @Param("assistantMessageId") UUID assistantMessageId,
                     @Param("crisisSeverity") Integer crisisSeverity,
                     @Param("now") OffsetDateTime now);
}
