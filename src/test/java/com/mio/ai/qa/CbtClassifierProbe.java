package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;

import java.util.function.Consumer;

/**
 * CBT 분류 호출이 <b>실제로 판정을 만들었는지</b>를 관측하는 {@link LlmClient} 데코레이터.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>프로덕션 {@code CbtMetadataClassifier.classify} 는 예외를 전부 삼키고
 * {@code CbtMetadataResult.none()} 을 돌려준다 (state=NONE, socratic=false). 하네스는 그것을
 * "개입 없음" 으로 읽어 {@code COMPLIANT} 로 센다. 즉 <b>분류 실패와 진짜 준수가 반환값에서
 * 구별되지 않는다.</b>
 *
 * <p>그리고 분류기 프롬프트의 {@code [Current Assistant Response]} 절에는 후보가 <b>쓴 본문이
 * 그대로</b> 들어간다. 그러므로 분류기의 JSON 파싱을 안정적으로 깨뜨리는 출력을 내는 후보는
 * 한 번도 채점되지 않은 채 준수율 100% 로 표에 오른다 — 이 저장소가 PR #464 에서 이미 한 번
 * 겪고 고친 실패 유형(빈 응답으로 100% 수용률을 얻은 후보)과 같은 종류다.
 *
 * <h2>왜 프로덕션을 고치지 않는가</h2>
 *
 * <p>고칠 필요가 없다. 하네스는 분류기에게 넘길 {@link LlmClient} 를 <b>자기가 만든다</b>
 * ({@code CellRunner} 생성자). 그 경계에 이 데코레이터를 끼우면 프로덕션이 삼킨 예외와,
 * 프로덕션이 조용히 기본값으로 접은 응답을 <b>호출 경계에서</b> 볼 수 있다. 프로덕션의 동작은
 * 한 글자도 바뀌지 않는다 — 예외는 그대로 다시 던져 프로덕션의 {@code catch} 가 평소대로
 * {@code none()} 을 만들게 두고, 응답 본문도 손대지 않고 그대로 넘긴다.
 * {@link RoleModelRewritingLlmClient} 가 모델 ID 에 대해 쓰는 것과 같은 수법이다.
 *
 * <h2>무엇을 실패로 세는가</h2>
 *
 * <p>세 가지다. 셋 다 프로덕션에서 {@code none()} 으로 접혀 준수와 구별되지 않는 경우다.
 *
 * <ol>
 *   <li>{@code completeJson} 이 예외를 던졌다 (네트워크·rate limit·모델 거부).</li>
 *   <li>응답이 JSON 객체로 파싱되지 않는다 — 프로덕션의 {@code objectMapper.readTree} 가
 *       던지고 {@code catch} 가 받는 자리.</li>
 *   <li>파싱은 됐지만 하네스가 읽는 두 축({@code cbt_intervention_state},
 *       {@code is_socratic}) 중 <b>어느 것도 채워지지 않았다.</b> 파싱 예외가 나지 않아
 *       프로덕션은 성공한 것처럼 보이지만, 판정을 만들 재료가 없어 결과는 역시
 *       {@code none()} 이다. "빈 응답은 어떤 검사도 통과한다" 와 같은 성질이라, 예외 유무가
 *       아니라 <b>판정이 만들어졌는가</b>로 세야 한다.</li>
 * </ol>
 *
 * <h2>파싱 규칙은 프로덕션의 거울이다</h2>
 *
 * <p>{@link #sanitize}는 {@code CbtMetadataClassifier.sanitizeJson} 과 같은 일을 한다 —
 * 코드펜스를 벗기고, {@code null} 은 {@code "{}"} 로 본다. 그쪽이 {@code private} 이라 부를 수
 * 없어 옮겨 적었고, 두 규칙이 어긋나면 이 프로브가 프로덕션이 <b>받아들이는</b> 응답을 실패로
 * 세게 된다. 그래서 {@code CellClassifierFailureTest} 가 프로덕션이 통과시키는 모양들을
 * 이 프로브도 통과시키는지 붙잡아 둔다.
 *
 * <h2>스레드</h2>
 *
 * <p>{@code CellRunner} 는 케이스를 4-way 병렬로 돈다. 관측은 {@link ThreadLocal} 로 두고,
 * 호출부가 {@link #beginObservation()} 직후 {@code classify(...)} 를 같은 스레드에서 부른 뒤
 * {@link #lastClassificationFailed()} 를 읽는다. 분류 1회 = {@code completeJson} 1회이므로
 * 이 짝짓기는 케이스 하나에 정확히 대응한다.
 */
final class CbtClassifierProbe implements LlmClient {

    /** 프로덕션 {@code CbtMetadataClassifier} 가 요청에 다는 태그. */
    static final String COMPONENT = "CBT_CLASSIFIER";

    /** 하네스가 개입 판정에 실제로 읽는 두 축 ({@code CellCaseOutcome.interventionObserved}). */
    static final String STATE_FIELD = "cbt_intervention_state";
    static final String SOCRATIC_FIELD = "is_socratic";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient delegate;
    private final ThreadLocal<Boolean> lastCallFailed = ThreadLocal.withInitial(() -> Boolean.FALSE);

    CbtClassifierProbe(LlmClient delegate) {
        this.delegate = delegate;
    }

    /** 이 스레드의 다음 분류 호출을 관측하기 위해 표식을 지운다. */
    void beginObservation() {
        lastCallFailed.set(Boolean.FALSE);
    }

    /**
     * 직전 분류 호출이 판정을 만들지 못했는가.
     *
     * <p>{@link #beginObservation()} 이후 분류 호출이 <b>아예 없었으면</b> 거짓이다. 프로덕션이
     * 응답이 비었다는 이유로 호출 없이 {@code none()} 을 돌려준 경우가 그렇고, 그것은 실패가
     * 아니라 진짜 "개입 없음" 이다.
     */
    boolean lastClassificationFailed() {
        return lastCallFailed.get();
    }

    @Override
    public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
        return delegate.stream(request, chunkHandler);
    }

    @Override
    public String completeText(LlmRequest request) {
        return delegate.completeText(request);
    }

    @Override
    public String completeJson(LlmRequest request) {
        if (!COMPONENT.equals(request.component())) {
            return delegate.completeJson(request);
        }
        String response;
        try {
            response = delegate.completeJson(request);
        } catch (RuntimeException e) {
            // 프로덕션의 catch 가 평소대로 none() 을 만들게 그대로 다시 던진다. 여기서
            // 삼키면 하네스가 프로덕션과 다른 코드를 재게 된다.
            lastCallFailed.set(Boolean.TRUE);
            throw e;
        }
        if (!yieldsJudgment(response)) {
            lastCallFailed.set(Boolean.TRUE);
        }
        return response;
    }

    /**
     * 이 응답으로 프로덕션이 실제 판정을 만들 수 있는가.
     *
     * <p>거짓이면 결과는 {@code none()} 이고, 하네스는 그것을 "개입 없음" 과 구별할 수 없다.
     */
    static boolean yieldsJudgment(String json) {
        try {
            JsonNode root = MAPPER.readTree(sanitize(json));
            return root != null && root.isObject()
                    && (root.hasNonNull(STATE_FIELD) || root.hasNonNull(SOCRATIC_FIELD));
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code CbtMetadataClassifier.sanitizeJson} 의 거울. 규칙이 어긋나면 프로브가 거짓 실패를 만든다. */
    private static String sanitize(String json) {
        if (json == null) {
            return "{}";
        }
        String sanitized = json.trim();
        if (sanitized.startsWith("```")) {
            sanitized = sanitized.replaceFirst("^```(?:json)?\\s*", "");
            sanitized = sanitized.replaceFirst("\\s*```$", "");
        }
        return sanitized.trim();
    }
}
