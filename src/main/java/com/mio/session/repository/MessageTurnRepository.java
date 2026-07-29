package com.mio.session.repository;

import com.mio.session.domain.MessageTurn;
import com.mio.session.domain.TurnStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
