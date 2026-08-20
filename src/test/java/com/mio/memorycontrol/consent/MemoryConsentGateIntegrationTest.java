package com.mio.memorycontrol.consent;

import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.memory.consolidation.ExtractorLlmClient;
import com.mio.ai.memory.consolidation.ExtractorResult;
import com.mio.ai.memory.consolidation.SessionConsolidator;
import com.mio.ai.memory.consolidation.SessionEndedEvent;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.memorycontrol.service.MemoryControlService;
import com.mio.report.domain.ReportWeek;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 동의 철회 후 신규 장기 기억 적재 중단 검증 (이슈 #453 완료 조건).
 *
 * <p>철회한 사용자의 세션이 끝나도 컨솔리데이션이 요약·사고·신념을 <b>한 건도</b> 만들지
 * 않아야 하고, LLM 호출 자체가 일어나지 않아야 한다. LLM 을 부른 뒤에 버리는 것은 게이트가
 * 아니다 — 사용자의 대화 원문이 이미 외부로 나간 뒤다.
 */
@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@ActiveProfiles("integration-test")
class MemoryConsentGateIntegrationTest {

    private static final long WAIT_TIMEOUT_MS = 15_000;

    @Autowired private SessionConsolidator sessionConsolidator;
    @Autowired private com.mio.ai.memory.consolidation.WeeklyReflectionJob weeklyReflectionJob;
    @Autowired private MemoryControlService memoryControlService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MessageEncryptor messageEncryptor;

    // OpenAiLlmClient 는 LlmClient·EmbeddingClient 겸용 구현체다. 인터페이스가 아니라
    // 구현체를 mock 해야 EmbeddingWorker 처럼 구현체 타입을 직접 주입받는 빈이 함께 만족된다.
    @MockBean private OpenAiLlmClient llmClient;
    @MockBean private ExtractorLlmClient extractorLlmClient;

    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "consent-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, ended_at) VALUES (?, ?, 'mio', 'ended', now())",
                sessionId, userId);
        jdbcTemplate.update(
                """
                INSERT INTO messages (id, session_id, user_id, role, content_ciphertext, content_dek_id)
                VALUES (?, ?, ?, 'user', ?, ?)
                """,
                UUID.randomUUID(), sessionId, userId,
                messageEncryptor.encrypt("오늘 발표를 망친 것 같아".getBytes(StandardCharsets.UTF_8)),
                messageEncryptor.dekId());

        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            emitChunk(invocation, "발표에 대한 걱정을 나눈 세션 요약.");
            return new LlmStreamResult(10, LlmUsage.unresolved("test"), false);
        });
        when(extractorLlmClient.extract(anyString(), any(), any()))
                .thenReturn(new ExtractorResult(List.of(), null, List.of(), "regular", null));
    }

    @Test
    @DisplayName("동의를 유지한 사용자는 세션 종료 후 요약이 적재된다 (게이트 오탐 방지 대조군)")
    void consentedUser_stillGetsConsolidation() {
        sessionConsolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 0));

        waitUntilSummaryStatusLeavesPending();
        Integer summaries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_summaries WHERE user_id = ?", Integer.class, userId);
        assertThat(summaries).isEqualTo(1);
    }

    @Test
    @DisplayName("동의 철회 후에는 신규 장기 기억이 한 건도 생성되지 않고 LLM 호출도 없다")
    void withdrawnUser_getsNoNewLongTermMemory() {
        memoryControlService.withdrawConsent(userId);

        sessionConsolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 0));

        waitUntilSummaryStatusLeavesPending();
        assertThat(countFor("session_summaries")).isZero();
        assertThat(countFor("thoughts")).isZero();
        assertThat(countFor("user_beliefs")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT summary_status FROM sessions WHERE id = ?", String.class, sessionId))
                .isEqualTo("failed");
        verifyNoInteractions(llmClient);
        verifyNoInteractions(extractorLlmClient);
    }

    @Test
    @DisplayName("게이트 통과 후 LLM 처리 중에 철회해도 영속화 직전 재확인이 적재를 막는다 (TOCTOU)")
    void withdrawalDuringConsolidation_abortsPersist() {
        // 세션 종료 → 진입 게이트는 동의 상태로 통과. LLM 스트리밍이 도는 사이(별도 스레드,
        // 별도 트랜잭션으로 커밋) 사용자가 동의를 철회한다. 철회의 일괄 비활성화는 아직
        // 커밋되지 않은 요약 행을 볼 수 없으므로, 영속화 직전 재확인이 없다면 이 요약은
        // active 로 남아 영구히 회수 가능해진다.
        when(llmClient.stream(any(), any())).thenAnswer(invocation -> {
            Thread withdrawer = new Thread(() -> memoryControlService.withdrawConsent(userId));
            withdrawer.start();
            withdrawer.join();
            emitChunk(invocation, "발표에 대한 걱정을 나눈 세션 요약.");
            return new LlmStreamResult(10, LlmUsage.unresolved("test"), false);
        });

        sessionConsolidator.onSessionEnded(new SessionEndedEvent(sessionId, userId, "mio", 0));

        waitUntilSummaryStatusLeavesPending();
        assertThat(countFor("session_summaries")).isZero();
        assertThat(countFor("thoughts")).isZero();
        assertThat(countFor("user_beliefs")).isZero();
        Integer activeSummaries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_summaries WHERE user_id = ? AND memory_status = 'active'",
                Integer.class, userId);
        assertThat(activeSummaries).isZero();
    }

    @Test
    @DisplayName("주간 회고는 철회 사용자를 LLM 호출·파생 저장 없이 건너뛰고, 동의 사용자는 처리한다")
    void weeklyReflection_skipsWithdrawnUserEntirely() {
        seedWeeklyActivity(userId);
        UUID consentedUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                consentedUserId, "consent-weekly-" + consentedUserId);
        seedWeeklyActivity(consentedUserId);

        memoryControlService.withdrawConsent(userId);
        when(llmClient.completeText(any())).thenReturn("주간 회고 텍스트");

        weeklyReflectionJob.run();

        // 철회 사용자: 외부 LLM 으로 어떤 집계도 나가지 않고, 파생 장기 상태도 쌓이지 않는다
        org.mockito.ArgumentCaptor<com.mio.ai.llm.LlmRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.mio.ai.llm.LlmRequest.class);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.atLeast(0))
                .completeText(captor.capture());
        assertThat(captor.getAllValues())
                .noneMatch(request -> userId.equals(request.userId()));
        assertThat(selfModelCount(userId)).isZero();

        // 동의 사용자(대조군): LLM 호출과 self-model 갱신이 일어난다
        assertThat(captor.getAllValues())
                .anyMatch(request -> consentedUserId.equals(request.userId()));
        assertThat(selfModelCount(consentedUserId)).isEqualTo(1);
    }

    /**
     * 스트리밍 청크를 흘려보낸다.
     *
     * <p>{@code any()} 스텁은 요약 생성 외에 청크 핸들러 없이 {@code stream} 을 부르는 경로
     * (렌더링·개인화)까지 함께 잡는다. 핸들러가 없으면 흘릴 곳이 없을 뿐 스텁이 깨질 일은
     * 아니므로 조용히 건너뛴다.
     */
    private void emitChunk(org.mockito.invocation.InvocationOnMock invocation, String chunk) {
        Consumer<String> chunkHandler = invocation.getArgument(1);
        if (chunkHandler != null) {
            chunkHandler.accept(chunk);
        }
    }

    /** 지난주 활동 흔적: 종료 세션 + 지배 감정 — 주간 회고 대상 선정과 집계에 걸리게 한다. */
    /**
     * 주간 회고 job 의 <b>대상 구간(직전 주 월~일) 안에</b> 활동을 심는다.
     *
     * <p>이전에는 {@code now() - interval '2 days'} 를 썼는데, 집계 쿼리에 상한이 없어
     * 그래도 걸려들었다. 이슈 #419 로 상한이 생기면서 "2일 전" 은 실행 요일에 따라
     * 이번 주로 떨어져 대상에서 빠진다 — 구간 안이라는 것을 날짜로 명시한다.
     */
    private void seedWeeklyActivity(UUID targetUserId) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        OffsetDateTime withinLastWeek = ReportWeek.lastWeekStartFrom(LocalDate.now(kst))
                .plusDays(2)              // 직전 주 수요일 — 경계에서 충분히 떨어뜨린다
                .atTime(12, 0)
                .atZone(kst)
                .toOffsetDateTime();

        UUID weeklySession = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sessions (id, user_id, character_id, status, started_at, ended_at)
                VALUES (?, ?, 'mio', 'ended', ?, ?)
                """,
                weeklySession, targetUserId, withinLastWeek, withinLastWeek);
        jdbcTemplate.update(
                """
                INSERT INTO emotional_states (user_id, source_event_id, primary_emotion, intensity, source, created_at)
                VALUES (?, ?, 'anxious', 60, 'chat', ?)
                """,
                targetUserId, weeklySession, withinLastWeek);
    }

    private int selfModelCount(UUID targetUserId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_self_model WHERE user_id = ?", Integer.class, targetUserId);
        return count == null ? 0 : count;
    }

    private int countFor(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** onSessionEnded 는 @Async 다 — summary_status 가 pending 을 벗어날 때까지 폴링한다. */
    private void waitUntilSummaryStatusLeavesPending() {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT summary_status FROM sessions WHERE id = ?", String.class, sessionId);
            if (status != null && !"pending".equals(status)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for consolidation", e);
            }
        }
        throw new AssertionError("consolidation did not finish within " + WAIT_TIMEOUT_MS + "ms");
    }
}
