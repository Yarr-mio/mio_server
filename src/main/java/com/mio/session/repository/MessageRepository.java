package com.mio.session.repository;

import com.mio.session.domain.Message;
import com.mio.session.domain.MessageRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT AVG(m.emotionScore) FROM Message m WHERE m.user.id = :userId AND m.createdAt >= :start AND m.createdAt < :end AND m.emotionScore IS NOT NULL")
    Double findAvgEmotionScore(@Param("userId") UUID userId,
                               @Param("start") OffsetDateTime start,
                               @Param("end") OffsetDateTime end);

    @Query("SELECT m.biasType, COUNT(m) FROM Message m WHERE m.user.id = :userId AND m.createdAt >= :start AND m.createdAt < :end AND m.biasType IS NOT NULL GROUP BY m.biasType ORDER BY COUNT(m) DESC")
    List<Object[]> findBiasTypeDistribution(@Param("userId") UUID userId,
                                            @Param("start") OffsetDateTime start,
                                            @Param("end") OffsetDateTime end);

    @Query("SELECT m FROM Message m WHERE m.session.id = :sessionId AND m.role = :role ORDER BY m.createdAt DESC")
    List<Message> findRecentBySessionAndRole(@Param("sessionId") UUID sessionId,
                                             @Param("role") MessageRole role,
                                             Pageable pageable);
}
