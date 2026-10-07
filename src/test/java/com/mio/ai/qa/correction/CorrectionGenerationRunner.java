package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.ModelRole;
import com.mio.ai.memory.working.WorkingMessage;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.prompt.PromptBuilder;
import com.mio.character.domain.CharacterPersona;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 정정 시험 케이스의 마지막 사용자 발화에 대한 응답을 생성한다 (이슈 #551).
 *
 * <p>프로덕션 생성 경로와 같은 것만 쓴다 — {@code PromptBuilder.buildSystemPrompt}, 최근 이력,
 * 스트리밍 호출, 출력 토큰 상한. 다른 점은 셋이다: (1) 이력에 AI 발화도 싣는다, (2) 안전 판정·
 * 메모리·체크포인트는 붙이지 않는다, (3) 응답 계획은 {@code UNPLANNED} 로 둔다 — ④ 의
 * 문제 턴이 그 상태였다.
 *
 * <p>온도·seed 를 지정하지 않는다. 프로덕션 요청 본문에도 없어서 API 기본값이 쓰이고, 같은
 * 입력의 응답이 매번 달라진다. 그래서 케이스마다 {@code repeats} 번 생성한다.
 *
 * <p>순차 실행이다. 병렬화는 요금·속도 제한 처리가 필요해 첫 버전에서 넣지 않았다.
 */
public final class CorrectionGenerationRunner {

    /**
     * 프로덕션 {@code ConversationOrchestrator.LLM_MAX_COMPLETION_TOKENS}(private)의 복제.
     * 같은 값인지 {@code CorrectionGenerationRunnerTest} 가 리플렉션으로 감시한다.
     */
    static final int PRODUCTION_MAX_COMPLETION_TOKENS = 400;

    /** 프로덕션 기본 생성 모델. canary 설정은 운영에서만 확인되므로 반영하지 않는다. */
    public static String productionModel() {
        return ModelCatalog.defaults().modelFor(ModelRole.GENERATION);
    }

    /**
     * @param failed    호출이 실패했거나 본문이 비었다. 외부 실패를 품질 실패와 섞지 않으려고 따로 센다
     * @param truncated 출력 토큰 상한에 걸려 잘렸다
     */
    public record Sample(String caseId, CorrectionPromptArm arm, int repeat, String response,
                         boolean failed, boolean truncated) {}

    /** 비용 귀속에 쓰는 고정 ID. 실제 사용자·세션이 아니라 평가 실행을 가리킨다. */
    static final UUID EVAL_USER = UUID.nameUUIDFromBytes("correction-eval-user".getBytes(StandardCharsets.UTF_8));
    static final UUID EVAL_SESSION = UUID.nameUUIDFromBytes("correction-eval-session".getBytes(StandardCharsets.UTF_8));

    private final LlmClient client;
    private final PromptBuilder promptBuilder;
    private final String model;
    private final GenerationMode mode;
    private final int maxCompletionTokens;
    private final String component;

    public CorrectionGenerationRunner(LlmClient client, PromptBuilder promptBuilder, String model,
                                      GenerationMode mode, int maxCompletionTokens) {
        this(client, promptBuilder, model, mode, maxCompletionTokens, null);
    }

    /**
     * @param component 비용 귀속 태그. {@code null} 이면 붙이지 않는다 — 태그가 없으면 프로덕션 클라이언트가
     *                  {@code ai_cost_events} 에 기록하지 않고 조용히 넘어간다. 실 LLM 측정만 태그를 붙이고,
     *                  비용은 저장소가 아니라 {@link CorrectionCostLedger} 로 모은다
     */
    public CorrectionGenerationRunner(LlmClient client, PromptBuilder promptBuilder, String model,
                                      GenerationMode mode, int maxCompletionTokens, String component) {
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.model = model;
        this.mode = mode;
        this.maxCompletionTokens = maxCompletionTokens;
        this.component = component;
    }

    /** 프로덕션 기본 모델·상한으로 만든다. */
    public static CorrectionGenerationRunner production(LlmClient client, GenerationMode mode) {
        return production(client, mode, null);
    }

    public static CorrectionGenerationRunner production(LlmClient client, GenerationMode mode, String component) {
        return new CorrectionGenerationRunner(client, new PromptBuilder(), productionModel(), mode,
                PRODUCTION_MAX_COMPLETION_TOKENS, component);
    }

    public String model() {
        return model;
    }

    public List<Sample> run(List<CorrectionEvalCase> cases, CorrectionPromptArm arm, int repeats) {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats 는 1 이상이어야 한다: " + repeats);
        }
        List<Sample> samples = new ArrayList<>();
        for (CorrectionEvalCase evalCase : cases) {
            for (int repeat = 0; repeat < repeats; repeat++) {
                samples.add(generateOne(evalCase, arm, repeat));
            }
        }
        return samples;
    }

    LlmRequest requestFor(CorrectionEvalCase evalCase, CorrectionPromptArm arm) {
        String systemPrompt = arm.apply(promptBuilder.buildSystemPrompt(
                mode, InterventionHints.empty(), null, CharacterPersona.DEFAULT.characterId(),
                null, ResponsePlan.unplanned()));
        List<WorkingMessage> history = new ArrayList<>();
        for (CorrectionEvalCase.Turn turn : evalCase.priorTurns()) {
            history.add(turn.isUser()
                    ? WorkingMessage.user(turn.text())
                    : WorkingMessage.assistant(turn.text()));
        }
        LlmRequest request = LlmRequest.of(model, systemPrompt, history, evalCase.userMessage())
                .withMaxCompletionTokens(maxCompletionTokens);
        return component == null ? request : request.withAttribution(component, EVAL_USER, EVAL_SESSION);
    }

    Sample generateOne(CorrectionEvalCase evalCase, CorrectionPromptArm arm, int repeat) {
        StringBuilder content = new StringBuilder();
        try {
            LlmStreamResult result = client.stream(requestFor(evalCase, arm), content::append);
            String response = content.toString();
            return new Sample(evalCase.id(), arm, repeat, response, response.isBlank(),
                    result != null && result.truncated());
        } catch (RuntimeException e) {
            return new Sample(evalCase.id(), arm, repeat, "", true, false);
        }
    }
}
