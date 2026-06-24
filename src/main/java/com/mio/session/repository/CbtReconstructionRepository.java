package com.mio.session.repository;

import com.mio.session.domain.CbtReconstruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface CbtReconstructionRepository extends JpaRepository<CbtReconstruction, UUID> {

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
