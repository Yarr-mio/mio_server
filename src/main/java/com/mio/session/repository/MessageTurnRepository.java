package com.mio.session.repository;

import com.mio.session.domain.MessageTurn;
import com.mio.session.domain.TurnStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 오래 {@code generating} 에 머문 턴을 터미널 상태로 회수한다 (이슈 #365).
     *
     * <p>프로세스가 죽으면 그 순간 진행 중이던 턴은 터미널 전이를 남기지 못한다. 회수하는
     * 코드가 없으면 영원히 {@code generating} 으로 남는다 — 스키마는
     * {@code idx_message_turns_generating} 부분 인덱스까지 두고 회수를 전제했지만 스위퍼가
     * 없었다.
     *
     * <p><b>리스 토큰을 보지 않는다.</b> 회수 대상은 정의상 소유자가 사라진 턴이고, 살아 있는
     * 시도는 {@code touchIfHeld} 로 {@code updated_at} 을 밀고 있으므로 시간 조건만으로
     * 충분하다. 토큰까지 맞추려 하면 죽은 프로세스의 토큰을 알 방법이 없어 아무것도 회수하지
     * 못한다.
     *
     * <p><b>트랜잭션 경계가 이 메서드 안에 있다.</b> 스케줄러 메서드에 {@code @Transactional}
     * 을 걸면 커밋이 메서드 반환 뒤 프록시에서 일어나, 스케줄러를 지키려고 감싼
     * {@code try/catch} 가 쿼리 실패만 잡고 커밋 실패는 놓친다 — 잡이 정상 종료한 것처럼
     * 보이면서 회수는 일어나지 않는 조합이다.
     *
     * @return 회수한 턴 수
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("""
            update MessageTurn t
               set t.status = com.mio.session.domain.TurnStatus.FAILED,
                   t.finishedReason = :finishedReason,
                   t.updatedAt = :now
             where t.status = com.mio.session.domain.TurnStatus.GENERATING
               and t.updatedAt < :staleBefore
            """)
    int abandonStaleGeneratingTurns(@Param("staleBefore") OffsetDateTime staleBefore,
                                    @Param("finishedReason") String finishedReason,
                                    @Param("now") OffsetDateTime now);
}
