package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.policy.GenerationMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 사용자가 후보 프롬프트와 직접 대화해 보는 진입점 (이슈 #551). <b>실 LLM 을 부른다 — 과금된다</b>
 * (턴당 대략 몇 원, 생성 호출 1번뿐).
 *
 * <pre>{@code
 * OPENAI_API_KEY=sk-... \
 * MIO_CHAT_FILE=eval-private/chat/session1.json \
 * MIO_CHAT_MESSAGE="휴식이 사치가 아니라 시간이 아예 없어요" \
 *   ./gradlew cleanTest test -PllmTests --tests "com.mio.ai.qa.correction.CorrectionChatSessionLlmTest" -i
 * }</pre>
 *
 * <p>한 턴마다 이 테스트를 다시 실행한다 — 대화 기록은 {@code MIO_CHAT_FILE} 에 쌓인다. 새 대화를
 * 시작하려면 {@code MIO_CHAT_RESET=1} 을 주거나 다른 파일 경로를 쓴다.
 *
 * <p>환경 변수:
 * <ul>
 *   <li>{@code MIO_CHAT_MODE} — NORMAL(기본) 또는 SUPPORTIVE</li>
 *   <li>{@code MIO_CHAT_ARM} — BASELINE(기본, 현재 프로덕션 — 이슈 #558로 정정 대응 블록이
 *       {@code PromptBuilder}에 반영된 뒤로는 이 팔이 그 블록을 포함한다) 또는
 *       WITH_CORRECTION_BLOCK(같은 블록을 한 번 더 중복해 붙임, 회귀 비교용으로만 남김 —
 *       기본으로 쓰면 정정 대응 지시가 두 번 들어간 프롬프트로 대화하게 된다)</li>
 * </ul>
 */
@Tag("llm-integration")
@DisplayName("[QA] 정정 대응 후보 프롬프트와 직접 대화 (실 LLM)")
class CorrectionChatSessionLlmTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("한 턴을 보내고 응답을 보여준 뒤 대화 기록에 이어 붙인다")
    void chatOneTurn() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && apiKey.startsWith("sk-"), "OPENAI_API_KEY 가 없다 — 건너뛴다");
        String message = System.getenv("MIO_CHAT_MESSAGE");
        Assumptions.assumeTrue(message != null && !message.isBlank(), "MIO_CHAT_MESSAGE 가 없다 — 건너뛴다");
        String fileEnv = System.getenv("MIO_CHAT_FILE");
        Path file = Path.of(fileEnv == null || fileEnv.isBlank() ? "eval-private/chat/session.json" : fileEnv);

        if ("1".equals(System.getenv("MIO_CHAT_RESET")) && Files.exists(file)) {
            Files.delete(file);
        }
        GenerationMode mode = GenerationMode.valueOf(orDefault(System.getenv("MIO_CHAT_MODE"), "NORMAL").toUpperCase());
        CorrectionPromptArm arm = CorrectionPromptArm.valueOf(
                orDefault(System.getenv("MIO_CHAT_ARM"), "BASELINE").toUpperCase());

        CorrectionCostLedger ledger = new CorrectionCostLedger();
        LlmClient client = CorrectionClients.real(apiKey, ledger);
        List<CorrectionChatSession.Turn> history = CorrectionChatSession.load(file);

        System.out.printf("%n[correction-chat] 파일=%s · 모드=%s · 팔=%s · 이전 턴 %d개%n",
                file, mode, arm, history.size());
        System.out.println("  사용자: " + message);

        CorrectionChatSession.Reply reply = CorrectionChatSession.sendAndAppend(
                client, mode, arm, CorrectionGenerationRunner.PRODUCTION_MAX_COMPLETION_TOKENS, history, message);

        System.out.println("  미오: " + reply.response());
        if (reply.truncated()) {
            System.out.println("  (경고: 출력 토큰 상한에 걸려 응답이 잘렸다)");
        }
        System.out.printf("  비용: $%s · 대화 기록 %d턴 저장됨%n",
                ledger.total().costUsd().setScale(5, java.math.RoundingMode.HALF_UP).toPlainString(),
                reply.history().size());

        CorrectionChatSession.save(file, reply.history());
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
