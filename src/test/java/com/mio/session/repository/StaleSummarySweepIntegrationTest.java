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
        jdbcTemplate.update("DELETE FROM behavior_tasks WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM session_summaries WHERE user_id = ?", user.getId());
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

    @Test
    @DisplayName("요약과 Todo 가 이미 만들어진 세션은 실패로 봉인하지 않고 완료로 회복시킨다")
    void sweep_recoversPendingSessionThatAlreadyHasSummaryAndTodo() {
        // consolidate() 는 요약을 독립 트랜잭션으로 먼저 커밋한다. 그 뒤 markDone 이 불리기 전에
        // 배포·크래시로 프로세스가 죽으면 완성된 요약을 두고도 pending 이 남는다.
        // 이때 실패로 확정하면 사용자는 존재하는 요약을 410 으로 영영 못 보게 된다.
        UUID sessionId = endedSessionWith(SummaryStatus.PENDING, OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
        insertSummary(sessionId);
        insertTodo(sessionId);

        staleSummarySweepJob.run();

        assertThat(statusOf(sessionId)).isEqualTo("done");
    }

    @Test
    @DisplayName("요약은 있으나 Todo가 없는 세션도 핵심 요약을 완료로 회복한다")
    void sweep_recoversPendingSessionWithoutTodo() {
        UUID sessionId = endedSessionWith(SummaryStatus.PENDING, OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
        insertSummary(sessionId);

        staleSummarySweepJob.run();

        assertThat(statusOf(sessionId)).isEqualTo("done");
    }

    private void insertSummary(UUID sessionId) {
        jdbcTemplate.update("""
                INSERT INTO session_summaries (user_id, session_id, character_id, summary_text)
                VALUES (?, ?, 'mio', '완성된 요약')
                """, user.getId(), sessionId);
    }

    private void insertTodo(UUID sessionId) {
        jdbcTemplate.update("""
                INSERT INTO behavior_tasks (user_id, source_session_id, generated_from, action_text, category)
                VALUES (?, ?, 'chat', '3분 호흡하기', '심리_안정')
                """, user.getId(), sessionId);
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
