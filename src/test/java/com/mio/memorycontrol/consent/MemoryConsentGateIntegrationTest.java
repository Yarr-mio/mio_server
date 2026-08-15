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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
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
            Consumer<String> chunkHandler = invocation.getArgument(1);
            chunkHandler.accept("발표에 대한 걱정을 나눈 세션 요약.");
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
