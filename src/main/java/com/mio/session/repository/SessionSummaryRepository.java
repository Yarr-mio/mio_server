package com.mio.session.repository;

import com.mio.session.domain.SessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SessionSummaryRepository extends JpaRepository<SessionSummary, UUID> {
    Optional<SessionSummary> findBySession_Id(UUID sessionId);

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
