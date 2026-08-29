package com.mio.session.repository;

import com.mio.session.domain.SessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionSummaryRepository extends JpaRepository<SessionSummary, UUID> {
    Optional<SessionSummary> findBySession_Id(UUID sessionId);

    /**
     * 리포트용 인지왜곡 분포 (이슈 #502).
     *
     * <p>{@code messages.bias_type} 은 실시간 위기감지(SafetyL1)용 경량 신호라 커버리지가
     * 낮다 — 정식 인지왜곡 분석은 {@code SessionConsolidator} 가 ExtractorLLM 으로 세션 종료
     * 후 만드는 이 컬럼({@code bias_types_detected})에 있다. JSONB 배열이라 네이티브 쿼리로만
     * 펼칠 수 있다.
     */
    @Query(value = """
            SELECT bt.bias_type AS biasType, COUNT(*) AS cnt
            FROM session_summaries s
            CROSS JOIN LATERAL jsonb_array_elements_text(s.bias_types_detected) AS bt(bias_type)
            WHERE s.user_id = :userId AND s.created_at >= :start AND s.created_at < :end
            GROUP BY bt.bias_type
            ORDER BY cnt DESC
            """, nativeQuery = true)
    List<Object[]> findBiasTypeDistribution(@Param("userId") UUID userId,
                                            @Param("start") OffsetDateTime start,
                                            @Param("end") OffsetDateTime end);

    /**
     * 사용자 노출용 요약을 채운다 (이슈 #339).
     *
     * <p>요약 본문이 이미 커밋된 뒤 별도 트랜잭션에서 호출되므로 엔티티를 다시 로드하지 않고
     * 컬럼만 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionSummary s SET s.userSummaryText = :userSummaryText WHERE s.session.id = :sessionId")
    int updateUserSummaryText(@Param("sessionId") UUID sessionId,
                              @Param("userSummaryText") String userSummaryText);
}
