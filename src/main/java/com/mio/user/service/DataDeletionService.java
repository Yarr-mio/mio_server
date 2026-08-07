package com.mio.user.service;

import com.mio.user.domain.DataDeletionRequest;
import com.mio.user.domain.User;
import com.mio.user.repository.DataDeletionRequestRepository;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 데이터 삭제 요청의 수명주기 (이슈 #373, 로드맵 §12 P0-6).
 *
 * <p>탈퇴 접수와 하드 삭제 실행을 나눈다. 접수는 사용자 요청 트랜잭션 안에서 일어나고,
 * 실행은 유예 기간 뒤 배치가 사용자 단위 독립 트랜잭션으로 처리한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataDeletionService {

    /**
     * 유예 기간.
     *
     * <p>{@code AuthService.WITHDRAW_RETENTION_DAYS} 와 같은 값이어야 한다. 두 곳에 있는
     * 이유는 접수 시점에 {@code scheduled_at} 을 <b>계산해 저장</b>하기 때문이다 — 정책이
     * 바뀌어도 이미 접수된 요청의 약속은 바뀌지 않는다.
     */
    private static final int RETENTION_DAYS = 30;

    /** 재시도 상한. 넘으면 {@code failed} 로 봉인하고 운영이 본다. */
    private static final int MAX_ATTEMPTS = 3;

    private final DataDeletionRequestRepository deletionRequestRepository;
    private final UserRepository userRepository;
    private final UserCachePurger cachePurger;

    /**
     * 탈퇴를 접수한다. 이미 진행 중인 요청이 있으면 그것을 돌려준다.
     *
     * <p>탈퇴 트랜잭션에 참여한다 — 사용자 소프트 삭제와 요청 접수가 함께 커밋되거나
     * 함께 롤백돼야 한다. 한쪽만 남으면 "탈퇴했는데 삭제 요청이 없는" 사용자가 생긴다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DataDeletionRequest requestDeletion(UUID userId, OffsetDateTime withdrawnAt) {
        return deletionRequestRepository.findActiveByUserId(userId)
                .orElseGet(() -> deletionRequestRepository.save(
                        DataDeletionRequest.open(userId, withdrawnAt.plusDays(RETENTION_DAYS))));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<DataDeletionRequest> findLatest(UUID userId) {
        return deletionRequestRepository.findTopByUserIdOrderByRequestedAtDesc(userId);
    }

    /**
     * 요청 하나를 처리한다. <b>사용자 단위 독립 트랜잭션이다.</b>
     *
     * <p>이전 {@code DataRetentionJob} 은 대상 전체를 한 번의 {@code deleteAll} 로 지웠다.
     * 그래서 한 사용자에서 실패하면 그 배치 전체가 롤백되고, 어느 사용자가 문제였는지도
     * 남지 않았다. 하나가 실패해도 나머지는 지워져야 한다.
     *
     * <p>순서는 캐시 → DB 다. DB 를 먼저 지우면 세션 ID 목록이 사라져 캐시 키를 만들 수
     * 없다 — 그러면 캐시는 TTL 까지 남고 그 사실을 알 방법도 없어진다.
     *
     * @return 완료했으면 {@code true}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean executeDeletion(UUID requestId) {
        DataDeletionRequest request = deletionRequestRepository.findById(requestId).orElse(null);
        if (request == null || request.getStatus().isTerminal()) {
            return false;
        }

        request.beginAttempt();
        try {
            if (request.getCachePurgedAt() == null) {
                cachePurger.purge(request.getUserId());
                request.markCachePurged();
            }

            User user = userRepository.findById(request.getUserId()).orElse(null);
            if (user != null) {
                // FK ON DELETE CASCADE 가 messages·session_summaries·memory_embeddings·
                // user_beliefs 등 파생물을 함께 지운다 (V13/V22/V40).
                userRepository.delete(user);
            }
            request.markDatabasePurged();
            request.complete();
            return true;

        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (request.getAttempts() >= MAX_ATTEMPTS) {
                log.error("DataDeletionService: giving up on requestId={} after {} attempts",
                        requestId, request.getAttempts(), e);
                request.fail(reason);
            } else {
                log.warn("DataDeletionService: attempt {} failed for requestId={}, will retry",
                        request.getAttempts(), requestId, e);
                request.deferWith(reason);
            }
            // 예외를 다시 던지지 않는다. 던지면 이 트랜잭션이 롤백되어 실패 기록까지
            // 사라지고, 다음 실행이 같은 실패를 처음처럼 다시 겪는다.
            return false;
        }
    }

    /** 유예 기간이 끝났는지. 배치가 판단 기준으로 쓴다. */
    public boolean isDue(DataDeletionRequest request) {
        return request.isDue(OffsetDateTime.now(ZoneOffset.UTC));
    }
}
