package com.mio.session.repository;

import com.mio.session.domain.CbtReconstruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface CbtReconstructionRepository extends JpaRepository<CbtReconstruction, UUID> {

    /**
     * 리포트용 평균 감정 점수 (이슈 #540) — CBT 개입 후 사용자가 직접 입력한 값만 집계한다.
     * AI 추정 신호({@code messages.emotion_score})는 Safety/Memory 내부용이라 여기 안 쓴다.
     */
    @Query("""
            SELECT AVG(c.emotionScoreAfter) FROM CbtReconstruction c
            WHERE c.user.id = :userId AND c.createdAt >= :start AND c.createdAt < :end
              AND c.emotionScoreAfter IS NOT NULL
            """)
    Double findAvgEmotionScoreAfter(
            @Param("userId") UUID userId,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CbtReconstruction c
            SET c.emotionScoreAfter = :score,
                c.updatedAt = :updatedAt
            WHERE c.id = :reconstructionId
              AND c.session.user.id = :userId
              AND c.emotionScoreAfter IS NULL
            """)
    int submitEmotionScoreAfterIfPending(
            @Param("reconstructionId") UUID reconstructionId,
            @Param("userId") UUID userId,
            @Param("score") Integer score,
            @Param("updatedAt") OffsetDateTime updatedAt);
}
