package com.mio.ai.qa.correction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 응답 하나를 정정 대응 기준표({@link CorrectionRubric})로 채점한다 (이슈 #551).
 *
 * <p><b>채점 모델은 설정으로 바꾼다.</b> 기본은 {@code gpt-4o-mini} 이고
 * {@code MIO_EVAL_CORRECTION_JUDGE_MODEL=<모델>} 환경 변수(또는 시스템 속성 {@code mio.eval.correction.judgeModel})로 갈아끼운다. 응답을 만든 모델과 같은 모델이
 * 자기 응답을 채점하면 후하게 주는 경향이 있을 수 있어, 생성 모델({@code gpt-4o})과 다른 모델을
 * 기본으로 둔다. 다만 채점기 자체의 정확도는 사람 채점과의 일치율로 따로 확인해야 한다.
 *
 * <p>응답이 어느 팔·모드에서 나왔는지는 채점기에 넘기지 않는다. 그래서 이 클래스의 입력에는
 * 그 정보가 아예 없다.
 */
public final class CorrectionJudge {

    public static final String MODEL_PROPERTY = "mio.eval.correction.judgeModel";
    /** 환경 변수로도 바꿀 수 있다. gradle 의 -D 옵션은 테스트 JVM 으로 전달되지 않지만 환경 변수는 전달된다. */
    public static final String MODEL_ENV = "MIO_EVAL_CORRECTION_JUDGE_MODEL";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    /** 판정 JSON + 인용은 짧지만, 잘리면 파싱이 통째로 실패하므로 여유를 둔다. */
    static final int MAX_COMPLETION_TOKENS = 600;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient client;
    private final String model;
    private final String component;
    private final int votes;
    private final int attempts;
    private final long backoffMillis;
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> failureReasons =
            new java.util.concurrent.ConcurrentHashMap<>();

    public CorrectionJudge(LlmClient client, String model) {
        this(client, model, null, 1);
    }

    public CorrectionJudge(LlmClient client, String model, String component) {
        this(client, model, component, 1);
    }

    /**
     * @param component 비용 귀속 태그. {@code null} 이면 붙이지 않는다 (생성 러너와 같은 규칙)
     * @param votes     같은 응답을 독립적으로 채점하는 횟수(홀수). 항목마다 다수결로 정한다 — 채점 모델이 같은
     *                  응답에도 판정을 바꾸는 흔들림(파일럿에서 표본의 약 6%)을 줄이려는 것이다
     */
    public CorrectionJudge(LlmClient client, String model, String component, int votes) {
        this(client, model, component, votes, 1, 0);
    }

    private CorrectionJudge(LlmClient client, String model, String component, int votes, int attempts,
                            long backoffMillis) {
        if (votes < 1 || votes % 2 == 0 || votes > 9) {
            throw new IllegalArgumentException("채점 투표 수는 1~9 사이의 홀수여야 한다: " + votes);
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("시도 횟수는 1 이상이어야 한다: " + attempts);
        }
        this.client = client;
        this.model = model;
        this.component = component;
        this.votes = votes;
        this.attempts = attempts;
        this.backoffMillis = backoffMillis;
    }

    /**
     * 채점 호출 한 번이 실패하면(호출 예외 또는 해석 불가한 출력) 최대 {@code attempts} 번까지 다시 시도한다.
     * 실 LLM 측정에서 속도 제한으로 채점 호출의 약 29% 가 실패해 표본이 빠진 것에 대한 대응이다 (iteration 1).
     * 시도 사이에 {@code backoffMillis × 시도 번호} 만큼 기다린다.
     */
    public CorrectionJudge withRetries(int attempts, long backoffMillis) {
        return new CorrectionJudge(client, model, component, votes, attempts, backoffMillis);
    }

    /** 실패 사유별 횟수(예외 종류와 짧은 메시지). 키 문자열은 가린다. */
    public java.util.Map<String, Integer> failureReasons() {
        return new java.util.TreeMap<>(failureReasons);
    }

    private void recordFailure(String reason) {
        String safe = reason.replaceAll("sk-[A-Za-z0-9_*-]+", "sk-***");
        failureReasons.merge(safe.length() > 90 ? safe.substring(0, 90) : safe, 1, Integer::sum);
    }


    public String model() {
        return model;
    }

    /** 시스템 속성, 환경 변수, 기본값 순으로 채점 모델을 정한다. */
    public static String configuredModel() {
        return configuredModel(System::getProperty, System::getenv);
    }

    static String configuredModel(Function<String, String> properties, Function<String, String> environment) {
        String configured = properties.apply(MODEL_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = environment.apply(MODEL_ENV);
        }
        return configured == null || configured.isBlank() ? DEFAULT_MODEL : configured.trim();
    }

    /** 채점 투표 수. 환경 변수 {@code MIO_EVAL_CORRECTION_JUDGE_VOTES}, 기본 3. */
    public static final String VOTES_ENV = "MIO_EVAL_CORRECTION_JUDGE_VOTES";
    public static final int DEFAULT_VOTES = 3;

    public static int configuredVotes() {
        return configuredVotes(System::getenv);
    }

    static int configuredVotes(Function<String, String> environment) {
        String raw = environment.apply(VOTES_ENV);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_VOTES;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value % 2 == 0 || value > 9) {
                throw new IllegalArgumentException(VOTES_ENV + " 는 1~9 사이의 홀수여야 한다: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(VOTES_ENV + " 는 정수여야 한다: " + raw);
        }
    }

    public int votes() {
        return votes;
    }

    /**
     * 같은 응답을 {@code votes} 번 독립적으로 채점하고 항목마다 다수결로 정한다. 실패한 투표는 세지 않고,
     * 성공한 투표가 하나도 없으면 채점 실패다. 동수(성공 투표가 짝수일 때)는 "아니오"로 둔다.
     * 통과 여부는 다수결로 정해진 항목에서 코드가 다시 계산한다.
     */
    public CorrectionVerdict judge(CorrectionEvalCase evalCase, String response) {
        if (response == null || response.isBlank()) {
            return CorrectionVerdict.failed(evalCase);
        }
        List<CorrectionVerdict> verdicts = new java.util.ArrayList<>();
        for (int i = 0; i < votes; i++) {
            CorrectionVerdict vote = judgeOnce(evalCase, response);
            if (!vote.judgeFailed()) {
                verdicts.add(vote);
            }
        }
        if (verdicts.isEmpty()) {
            return CorrectionVerdict.failed(evalCase);
        }
        return verdicts.size() == 1 ? verdicts.get(0) : majority(evalCase, verdicts);
    }

    private CorrectionVerdict majority(CorrectionEvalCase evalCase, List<CorrectionVerdict> verdicts) {
        Map<String, Boolean> items = new LinkedHashMap<>();
        Map<String, String> evidence = new LinkedHashMap<>();
        for (String key : verdicts.get(0).items().keySet()) {
            long yes = verdicts.stream().filter(v -> Boolean.TRUE.equals(v.items().get(key))).count();
            boolean value = yes * 2 > verdicts.size();
            items.put(key, value);
            if (value) {
                verdicts.stream().filter(v -> Boolean.TRUE.equals(v.items().get(key)) && v.evidence().containsKey(key))
                        .findFirst().ifPresent(v -> evidence.put(key, v.evidence().get(key)));
            }
        }
        return evalCase.correction()
                ? CorrectionVerdict.forCorrection(evalCase, items, evidence)
                : CorrectionVerdict.forControl(evalCase, items, evidence);
    }

    private CorrectionVerdict judgeOnce(CorrectionEvalCase evalCase, String response) {
        String system = evalCase.correction()
                ? CorrectionRubric.CORRECTION_SYSTEM_PROMPT
                : CorrectionRubric.CONTROL_SYSTEM_PROMPT;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                LlmRequest request = LlmRequest.of(model, system, userPrompt(evalCase, response))
                        .withMaxCompletionTokens(MAX_COMPLETION_TOKENS);
                if (component != null) {
                    request = request.withAttribution(component, CorrectionGenerationRunner.EVAL_USER,
                            CorrectionGenerationRunner.EVAL_SESSION);
                }
                CorrectionVerdict verdict = parse(evalCase, client.completeJson(request));
                if (!verdict.judgeFailed()) {
                    return verdict;
                }
                recordFailure("출력 해석 실패");
            } catch (RuntimeException e) {
                recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (attempt < attempts && backoffMillis > 0) {
                try {
                    Thread.sleep(backoffMillis * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return CorrectionVerdict.failed(evalCase);
    }

    String userPrompt(CorrectionEvalCase evalCase, String response) {
        StringBuilder sb = new StringBuilder();
        sb.append("[라벨]\n");
        sb.append("type: ").append(evalCase.type()).append('\n');
        if (evalCase.correction()) {
            sb.append("strength: ").append(evalCase.strength()).append('\n');
        } else {
            sb.append("adviceRequested: ").append(evalCase.adviceRequested()).append('\n');
        }
        sb.append("\n[대화]\n");
        for (CorrectionEvalCase.Turn turn : evalCase.turns()) {
            sb.append(turn.role()).append(": ").append(turn.text()).append('\n');
        }
        sb.append("\n[평가할 응답]\n").append(response);
        return sb.toString();
    }

    private CorrectionVerdict parse(CorrectionEvalCase evalCase, String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return CorrectionVerdict.failed(evalCase);
        }
        List<String> keys = evalCase.correction()
                ? CorrectionVerdict.CORRECTION_ITEMS
                : CorrectionVerdict.CONTROL_ITEMS;
        Map<String, Boolean> items = new LinkedHashMap<>();
        for (String key : keys) {
            JsonNode value = root == null ? null : root.get(key);
            if (value == null || !value.isBoolean()) {
                // 항목 하나라도 빠지거나 불리언이 아니면 부분 결과로 통과를 계산하지 않는다.
                return CorrectionVerdict.failed(evalCase);
            }
            items.put(key, value.asBoolean());
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        JsonNode evidenceNode = root.get("evidence");
        if (evidenceNode != null && evidenceNode.isObject()) {
            evidenceNode.fields().forEachRemaining(entry -> {
                if (items.containsKey(entry.getKey()) && entry.getValue().isTextual()) {
                    evidence.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return evalCase.correction()
                ? CorrectionVerdict.forCorrection(evalCase, items, evidence)
                : CorrectionVerdict.forControl(evalCase, items, evidence);
    }
}
