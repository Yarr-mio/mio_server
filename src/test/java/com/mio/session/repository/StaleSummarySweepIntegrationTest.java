package com.mio.session.repository;

import com.mio.session.domain.Session;
import com.mio.session.domain.SummaryStatus;
import com.mio.session.job.StaleSummarySweepJob;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고착된 pending 요약 정리의 실제 DB 동작 검증 (이슈 #356).
 *
 * <p>목으로는 확인할 수 없는 두 가지를 잡는다.
 *
 * <ul>
 *   <li>{@code summary_status} 는 {@link com.mio.session.domain.SummaryStatusConverter} 로 문자열에
 *       매핑되는 컬럼이다. 벌크 UPDATE 의 enum 리터럴 변환이 어긋나면 쿼리는 조용히 0건을
 *       갱신하고 무한 로딩은 그대로 남는다.</li>
 *   <li>스케줄러에는 트랜잭션이 없다. 정리 쿼리가 자기 트랜잭션 경계를 갖지 못하면
 *       {@code TransactionRequiredException} 으로 매번 실패한다. 그래서 리포지터리가 아니라
 *       Job 을 호출하고, 테스트에 트랜잭션을 걸지 않는다.</li>
 * </ul>
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class StaleSummarySweepIntegrationTest {

    @Autowired private StaleSummarySweepJob staleSummarySweepJob;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User user;

    @BeforeEach
    void setUp() {
        User newUser = User.builder()
                .socialProvider("kakao")
                .socialId("stale-summary-it-" + UUID.randomUUID())
                .privacyConsent(true)
                .build();
        newUser.completeOnboarding("mio");
        user = userRepository.save(newUser);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sessions WHERE user_id = ?", user.getId());
        userRepository.deleteById(user.getId());
    }

    @Test
    @DisplayName("유예를 넘긴 pending 요약만 실패로 정리하고 진행 중·완료 세션은 건드리지 않는다")
    void sweep_marksOnlyStalePendingFailed() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID stale = endedSessionWith(SummaryStatus.PENDING, now.minusHours(3));
        UUID recent = endedSessionWith(SummaryStatus.PENDING, now.minusMinutes(1));
        UUID done = endedSessionWith(SummaryStatus.DONE, now.minusHours(3));
        UUID active = activeSession();

        staleSummarySweepJob.run();

        assertThat(statusOf(stale)).isEqualTo("failed");
        // 아직 컨솔리데이션이 돌고 있을 수 있는 세션의 요약을 가로채면 안 된다.
        assertThat(statusOf(recent)).isEqualTo("pending");
        // 뒤늦게 완료된 컨솔리데이션의 done 을 덮어쓰면 사용자가 받을 요약을 잃는다.
        assertThat(statusOf(done)).isEqualTo("done");
        assertThat(statusOf(active)).isEqualTo("pending");
    }

    private UUID endedSessionWith(SummaryStatus summaryStatus, OffsetDateTime endedAt) {
        UUID id = activeSession();
        // end() 는 endedAt 을 현재 시각으로 고정하므로, 유예 경계를 만들려면 직접 써야 한다.
        jdbcTemplate.update(
                "UPDATE sessions SET status = 'ended', ended_at = ?, summary_status = ? WHERE id = ?",
                endedAt, summaryStatus.value(), id);
        return id;
    }

    private UUID activeSession() {
        return sessionRepository.save(Session.builder()
                .user(user)
                .characterId("mio")
                .build()).getId();
    }

    private String statusOf(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT summary_status FROM sessions WHERE id = ?", String.class, sessionId);
    }
}
