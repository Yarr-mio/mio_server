package com.mio.user.service;

import com.mio.user.domain.DataDeletionRequest;
import com.mio.user.domain.DeletionStatus;
import com.mio.user.job.DataRetentionJob;
import com.mio.user.repository.DataDeletionRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 삭제 SLA 검증 (이슈 #373, 로드맵 §12 P0-6 완료 조건).
 *
 * <p>완료 조건은 "DB/vector/cache/파생물 삭제 SLA 검증" 이다. FK cascade 가 있다는 것과
 * <b>실제로 지워지는 것</b>은 다르다 — 지금까지 그것을 확인하는 테스트가 하나도 없었다.
 *
 * <p>{@code @Transactional} 을 쓰지 않는다. 앰비언트 트랜잭션 안에서 확인하면 커밋되지
 * 않은 상태를 보게 되고, Redis 는 애초에 트랜잭션 밖이라 시점이 어긋난다.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class DataDeletionIntegrationTest {

    @Autowired
    private DataDeletionService deletionService;

    @Autowired
    private DataDeletionRequestRepository deletionRequestRepository;

    @Autowired
    private DataRetentionJob retentionJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "deletion-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id) VALUES (?, ?, 'mio')",
                sessionId, userId);
        // 파생물 — 삭제가 여기까지 전파되는지가 이 테스트의 핵심이다.
        jdbcTemplate.update(
                """
                INSERT INTO session_summaries (id, user_id, session_id, character_id, summary_text, embedding_status)
                VALUES (?, ?, ?, 'mio', '테스트 요약', 'pending')
                """,
                UUID.randomUUID(), userId, sessionId);

        seedCache();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        redisTemplate.delete(cacheKeys());
    }

    @Test
    @DisplayName("유예 기간이 지난 요청을 처리하면 DB·파생물·캐시가 모두 사라진다")
    void hardDeleteRemovesEveryStore() {
        DataDeletionRequest request = openDueRequest();

        retentionJob.hardDeleteExpiredUsers();

        assertThat(userRows()).as("사용자 행").isZero();
        assertThat(sessionRows()).as("세션 (FK cascade)").isZero();
        assertThat(summaryRows()).as("세션 요약 — 파생물이자 벡터 보관처").isZero();
        assertThat(remainingCacheKeys())
                .as("Redis 캐시. 이전에는 TTL 만료에만 의존해 최대 90분 남았다")
                .isZero();

        DataDeletionRequest completedRequest = deletionRequestRepository.findById(request.getId())
                .orElseThrow();
        assertThat(completedRequest.getStatus()).isEqualTo(DeletionStatus.COMPLETED);
        assertThat(completedRequest.getCachePurgedAt()).isNotNull();
        assertThat(completedRequest.getDatabasePurgedAt()).isNotNull();
        assertThat(completedRequest.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("유예 기간 전에는 아무것도 지우지 않는다")
    void doesNotDeleteBeforeTheGracePeriodEnds() {
        openRequestScheduledAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30));

        retentionJob.hardDeleteExpiredUsers();

        assertThat(userRows()).isEqualTo(1);
        assertThat(remainingCacheKeys()).isEqualTo(cacheKeys().size());
    }

    @Test
    @DisplayName("삭제 상태를 조회할 수 있고 저장소별 진행이 남는다")
    void deletionProgressIsQueryable() {
        DataDeletionRequest request = openDueRequest();

        assertThat(deletionService.findLatest(userId))
                .as("접수 직후에도 상태를 물어볼 수 있어야 한다")
                .isPresent()
                .get()
                .satisfies(found -> assertThat(found.getStatus()).isEqualTo(DeletionStatus.PENDING));

        boolean completed = deletionService.executeDeletion(request.getId());

        assertThat(completed).isTrue();
        assertThat(userRows()).isZero();
        assertThat(deletionService.findByOperationId(request.getId()))
                .isPresent()
                .get()
                .satisfies(found -> {
                    assertThat(found.getStatus()).isEqualTo(DeletionStatus.COMPLETED);
                    assertThat(found.getDatabasePurgedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("탈퇴를 두 번 접수해도 요청은 하나다")
    void repeatedRequestsReuseTheActiveOne() {
        OffsetDateTime withdrawnAt = OffsetDateTime.now(ZoneOffset.UTC);
        DataDeletionRequest first = inTransaction(() ->
                deletionService.requestDeletion(userId, withdrawnAt));
        DataDeletionRequest second = inTransaction(() ->
                deletionService.requestDeletion(userId, withdrawnAt));

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────

    private DataDeletionRequest openDueRequest() {
        // 유예 기간이 이미 끝난 요청.
        return openRequestScheduledAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    }

    private DataDeletionRequest openRequestScheduledAt(OffsetDateTime scheduledAt) {
        UUID requestId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO data_deletion_requests (id, user_id, status, scheduled_at)
                VALUES (?, ?, 'pending', ?)
                """,
                requestId, userId, scheduledAt);
        return deletionRequestRepository.findById(requestId).orElseThrow();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> action.get());
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private void seedCache() {
        for (String key : cacheKeys()) {
            redisTemplate.opsForValue().set(key, "seeded", Duration.ofMinutes(30));
        }
    }

    private java.util.List<String> cacheKeys() {
        return java.util.List.of(
                "session:%s:messages".formatted(sessionId),
                "session:%s:working".formatted(sessionId),
                "session:%s:safety_profile".formatted(sessionId),
                "session:%s:context_cache".formatted(sessionId),
                "user:%s:unrecorded_crisis".formatted(userId)
        );
    }

    private long remainingCacheKeys() {
        return cacheKeys().stream()
                .filter(key -> Boolean.TRUE.equals(redisTemplate.hasKey(key)))
                .count();
    }

    private int userRows() {
        return count("SELECT COUNT(*) FROM users WHERE id = ?", userId);
    }

    private int sessionRows() {
        return count("SELECT COUNT(*) FROM sessions WHERE user_id = ?", userId);
    }

    private int summaryRows() {
        return count("SELECT COUNT(*) FROM session_summaries WHERE user_id = ?", userId);
    }

    private int count(String sql, UUID param) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, param);
        return value != null ? value : 0;
    }
}
