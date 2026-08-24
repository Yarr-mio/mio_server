package com.mio.session.repository;

import com.mio.session.domain.Message;
import com.mio.session.domain.MessageKind;
import com.mio.session.domain.MessageRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT AVG(m.emotionScore) FROM Message m WHERE m.user.id = :userId AND m.createdAt >= :start AND m.createdAt < :end AND m.emotionScore IS NOT NULL")
    Double findAvgEmotionScore(@Param("userId") UUID userId,
                               @Param("start") OffsetDateTime start,
                               @Param("end") OffsetDateTime end);

    @Query("SELECT m FROM Message m WHERE m.session.id = :sessionId AND m.role = :role ORDER BY m.createdAt DESC")
    List<Message> findRecentBySessionAndRole(@Param("sessionId") UUID sessionId,
                                             @Param("role") MessageRole role,
                                             Pageable pageable);

    List<Message> findBySession_IdOrderByCreatedAtAsc(UUID sessionId);

    /** 세션의 선제 인사. 재진입 복구에 쓴다 — 세션당 1건이 DB 제약으로 보장된다 (이슈 #530). */
    Optional<Message> findBySession_IdAndMessageKind(UUID sessionId, MessageKind messageKind);

    /**
     * 사용자의 최근 선제 인사 문구 식별자 (이슈 #530).
     *
     * <p>직전에 쓴 문구를 로테이션 후보에서 빼기 위한 조회다. 본문이 아니라 코드만 읽어
     * 복호화 비용을 들이지 않는다. {@code Pageable} 로 1건만 가져온다.
     */
    @Query("SELECT m.openingVariant FROM Message m "
            + "WHERE m.user.id = :userId AND m.messageKind = :messageKind "
            + "ORDER BY m.createdAt DESC")
    List<String> findRecentOpeningVariants(@Param("userId") UUID userId,
                                           @Param("messageKind") MessageKind messageKind,
                                           Pageable pageable);

    /** 세션 대화 이력 첫 페이지 — 오래된 순 (이슈 #531). */
    @Query("SELECT m FROM Message m WHERE m.session.id = :sessionId "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<Message> findHistoryFirstPage(@Param("sessionId") UUID sessionId, Pageable pageable);

    /**
     * 세션 대화 이력 다음 페이지 — keyset 페이지네이션 (이슈 #531).
     *
     * <p>offset 이 아니라 {@code (created_at, id)} 기준으로 이어 받는다. offset 은 조회 중 새
     * 메시지가 들어오면 경계가 밀려 중복·누락이 생긴다. {@code created_at} 만으로는 같은
     * 시각에 저장된 메시지에서 순서가 흔들리므로 {@code id} 를 tie-break 로 둔다.
     */
    @Query("SELECT m FROM Message m WHERE m.session.id = :sessionId "
            + "AND (m.createdAt > :afterCreatedAt "
            + "     OR (m.createdAt = :afterCreatedAt AND m.id > :afterId)) "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    List<Message> findHistoryAfter(@Param("sessionId") UUID sessionId,
                                   @Param("afterCreatedAt") OffsetDateTime afterCreatedAt,
                                   @Param("afterId") UUID afterId,
                                   Pageable pageable);
}
