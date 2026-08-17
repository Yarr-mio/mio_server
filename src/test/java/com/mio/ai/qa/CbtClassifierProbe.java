package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.judge.CbtInterventionState;
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
 * <p>네 가지다. 넷 다 프로덕션에서 {@code none()} 으로 접혀 준수와 구별되지 않는 경우다.
 *
 * <ol>
 *   <li>{@code completeJson} 이 예외를 던졌다 (네트워크·rate limit·모델 거부).</li>
 *   <li>응답이 JSON 객체로 파싱되지 않는다 — 프로덕션의 {@code objectMapper.readTree} 가
 *       던지고 {@code catch} 가 받는 자리.</li>
 *   <li>파싱은 됐지만 하네스가 읽는 두 축({@code cbt_intervention_state},
 *       {@code is_socratic}) 중 <b>어느 것도 채워지지 않았다.</b> 파싱 예외가 나지 않아
 *       프로덕션은 성공한 것처럼 보이지만, 판정을 만들 재료가 없어 결과는 역시
 *       {@code none()} 이다.</li>
 *   <li><b>값이 프로덕션의 어휘 밖이다.</b> {@code CbtInterventionState.fromWireValue} 는
 *       모르는 문자열을 <b>예외 없이 조용히</b> {@link CbtInterventionState#NONE} 으로 접는다.
 *       그래서 {@code {"cbt_intervention_state":"asked_something","is_socratic":false}} 는
 *       필드가 채워져 있고 파싱도 되지만 프로덕션이 읽어 내는 값은 여전히 "개입 없음" 이다.
 *       필드 <b>존재</b>만 보면 이 PR 이 막으려던 우회가 한 겹 아래에 그대로 남는다 —
 *       후보 텍스트가 분류기 프롬프트에 그대로 들어가는 이 프로브 자신의 위협 모델에서
 *       충분히 개연적인 경로다. {@code is_socratic} 도 같은 이유로 <b>불리언 노드인지</b>까지
 *       본다. {@code "is_socratic":"yes"} 는 {@code asBoolean(false)} 를 지나 조용히 false 가
 *       되고, false 는 곧 준수이기 때문이다.</li>
 * </ol>
 *
 * <h2>한쪽 축만 신뢰할 수 있을 때 (부분 충족)</h2>
 *
 * <p>하네스의 개입 판정은 두 축의 <b>OR</b> 이다
 * ({@code CellCaseOutcome.interventionObserved}). 그래서 결론의 방향에 따라 필요한 근거가
 * 다르다.
 *
 * <ul>
 *   <li><b>"개입 있음" 은 한쪽만으로 선다.</b> 신뢰할 수 있는 축 하나가 참이면 OR 은 이미
 *       참이고, 다른 축이 쓰레기여도 결론이 바뀌지 않는다 → 판정 성립.</li>
 *   <li><b>"개입 없음" 은 두 축이 모두 신뢰할 수 있어야 선다.</b> 한 축이 어휘 밖이면 그
 *       축의 참값이 {@code socratic_asked} 였을 수 있고, 그러면 OR 의 거짓은 근거 없이
 *       나온 것이다 → 실패로 센다.</li>
 * </ul>
 *
 * <p>이 비대칭은 의도적이며 fail-closed 방향이다. 거짓(=준수)이 이 지표를 부풀리는 방향이므로
 * 그쪽에만 두 축을 요구한다.
 *
 * <h2>파싱 규칙은 프로덕션의 거울이다</h2>
 *
 * <p>{@link #sanitize}는 {@code CbtMetadataClassifier.sanitizeJson} 과 같은 일을 하고,
 * 어휘 검사는 프로덕션 {@link CbtInterventionState#fromWireValue} 에 <b>위임한다</b> —
 * 옮겨 적지 않았으므로 enum 에 상태가 추가되면 이 프로브가 자동으로 따라간다.
 * {@code sanitizeJson} 쪽은 {@code private} 이라 부를 수 없어 옮겨 적었고, 두 규칙이 어긋나면
 * 이 프로브가 프로덕션이 <b>받아들이는</b> 응답을 실패로 세게 된다. 그래서
 * {@code CellClassifierFailureTest} 가 같은 fixture 를 <b>프로덕션 실경로</b>
 * ({@code CbtMetadataClassifier.classify}) 에 태워 이 프로브의 판정과 대조한다 — 하드코딩된
 * 기대값 두 벌을 맞대는 것은 고정이 아니다.
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
     *
     * <p>필드 <b>존재</b>가 아니라 <b>값이 프로덕션의 어휘에 속하는지</b>를 본다. 클래스
     * 주석의 "부분 충족" 절이 두 축의 비대칭을 설명한다.
     */
    static boolean yieldsJudgment(String json) {
        try {
            JsonNode root = MAPPER.readTree(sanitize(json));
            if (root == null || !root.isObject()) {
                return false;
            }
            JsonNode state = root.path(STATE_FIELD);
            JsonNode socratic = root.path(SOCRATIC_FIELD);
            boolean stateTrustworthy = inVocabulary(state);
            // asBoolean(false) 는 불리언이 아닌 노드도 조용히 삼킨다. 불리언 노드일 때만
            // "모델이 이 축을 실제로 말했다" 고 볼 수 있다.
            boolean socraticTrustworthy = socratic.isBoolean();

            // "개입 있음" 은 신뢰할 수 있는 축 하나로 선다 — OR 이라 다른 축이 결론을 못 바꾼다.
            if (socraticTrustworthy && socratic.booleanValue()) {
                return true;
            }
            if (stateTrustworthy && CbtInterventionState.fromWireValue(state.asText())
                    == CbtInterventionState.SOCRATIC_ASKED) {
                return true;
            }
            // "개입 없음" 은 두 축이 모두 신뢰할 수 있어야 선다. 한 축이 어휘 밖이면 그 축의
            // 참값이 socratic_asked 였을 수 있고, 그러면 이 거짓은 근거 없이 나온 것이다.
            return stateTrustworthy && socraticTrustworthy;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 상태 문자열이 프로덕션의 어휘에 속하는가.
     *
     * <p>{@link CbtInterventionState#fromWireValue} 에 위임한다 — 옮겨 적으면 enum 에 상태가
     * 추가될 때 이 프로브만 낡는다. 다만 그 함수는 <b>모르는 값도 {@code NONE} 으로</b>
     * 돌려주므로, {@code NONE} 이 나왔을 때는 입력이 정말 {@code "none"} 이었는지 되물어야
     * 어휘 소속을 판별할 수 있다.
     */
    private static boolean inVocabulary(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return false;
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return CbtInterventionState.fromWireValue(normalized) != CbtInterventionState.NONE
                || CbtInterventionState.NONE.wireValue().equals(normalized);
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
