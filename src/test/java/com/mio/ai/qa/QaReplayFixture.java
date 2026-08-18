package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 실제 QA 케이스를 제품 경로로 재생하기 위한 익명화 multi-turn fixture (이슈 #455, 로드맵 §12 P0-10).
 *
 * <p>형식 문서는 {@code src/test/resources/qa/fixtures/README.md} 에 있다. 필드 하나가 오타로
 * 어긋나면 그 케이스는 조용히 아무것도 검증하지 않게 되므로, 로더는 알 수 없는 필드를
 * 실패로 처리하고({@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES}) 필수 필드
 * 부재를 {@link #validate()} 에서 즉시 터뜨린다.
 */
record QaReplayFixture(
        String caseId,
        String sourceQa,
        String description,
        Correlation correlation,
        List<Turn> turns
) {

    /** fixture 파일은 QA 기록과 같은 snake_case 를 쓴다. */
    private static final ObjectMapper FIXTURE_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    static QaReplayFixture load(InputStream in, String origin) throws IOException {
        QaReplayFixture fixture = FIXTURE_MAPPER.readValue(in, QaReplayFixture.class);
        fixture.validate(origin);
        return fixture;
    }

    /**
     * QA 필수 상관관계 메타데이터 (이슈 #455 계약).
     *
     * <p>버전 값들은 아직 QA 기록에 채워지지 않아 {@code null} 을 허용하지만, <b>키 자체는
     * fixture 에 반드시 존재해야 한다</b> — 새 fixture 를 만들 때 어떤 버전 조합에서 잡힌
     * 결함인지 적을 자리를 강제하기 위해서다. envelope 버전은 P1-10(ResponseEnvelope)
     * 도입 후 필수화된다.
     */
    record Correlation(
            String modelVersion,
            String promptVersion,
            String policyVersion,
            String envelopeVersion
    ) {}

    record Turn(
            String name,
            String userMessage,
            Stubs stubs,
            Expect expect
    ) {}

    /**
     * 이 턴의 외부 경계(LLM·Moderation) 스텁. 그 외 전 구간은 실제 빈으로 실행된다.
     *
     * @param moderationSelfHarmFlagged L0 Moderation 이 self-harm 으로 판정했는지
     * @param llmStreamChunks 생성 LLM 이 흘릴 청크. {@code null} 이면 이 턴은 생성 LLM 을
     *                        호출하면 안 되는 턴이라는 뜻이고, 호출되면 즉시 실패한다.
     * @param inputJudgeJson  INPUT_JUDGE 가 반환할 JSON. {@code null} 이면 CLEAR_LOW 기본값.
     * @param outputJudgeJson OUTPUT_JUDGE 가 반환할 JSON. {@code null} 이면 SEND 기본값.
     * @param cbtClassifierJson CBT_CLASSIFIER 가 반환할 JSON. {@code null} 이면 none 기본값.
     */
    record Stubs(
            boolean moderationSelfHarmFlagged,
            List<String> llmStreamChunks,
            JsonNode inputJudgeJson,
            JsonNode outputJudgeJson,
            JsonNode cbtClassifierJson
    ) {
        static Stubs defaults() {
            return new Stubs(false, null, null, null, null);
        }
    }

    /**
     * 이 턴이 사용자에게 최종 노출돼야 하는 결과.
     *
     * @param sseEvents 이벤트 종류의 순서. 연속된 {@code delta} 는 하나로 접어 비교한다 —
     *                  청크 개수가 아니라 노출 계약(session_meta → delta/crisis → done)을
     *                  고정하는 것이 목적이다.
     * @param llmStreamCalled 생성 LLM 스트림이 호출됐어야 하는지. {@code null} 이면 검사하지 않는다.
     */
    record Expect(
            List<String> sseEvents,
            FinalText finalText,
            Crisis crisis,
            Done done,
            Boolean llmStreamCalled
    ) {}

    /**
     * 전달 텍스트 제약.
     *
     * @param exact       최종 화면에 남는 텍스트와 정확히 일치해야 하는 값 (null 이면 미검사)
     * @param contains    최종 화면에 남는 텍스트가 포함해야 하는 조각들
     * @param notContains <b>어느 시점에도</b> 전달되면 안 되는 조각들 — delta 로 나갔다가
     *                    delta.replace 로 덮인 텍스트도 노출로 센다
     */
    record FinalText(
            String exact,
            List<String> contains,
            List<String> notContains
    ) {}

    record Crisis(
            int severity,
            int minHotlines,
            List<String> hotlineNumbers
    ) {}

    /** {@code null} 필드는 검사하지 않는다. {@code finishedReason} 만 필수다. */
    record Done(
            String finishedReason,
            Boolean isCrisisFlagged,
            Boolean isSocratic,
            String cbtInterventionState,
            Integer emotionScore
    ) {}

    private void validate(String origin) {
        require(caseId != null && !caseId.isBlank(), origin, "case_id 가 없다");
        require(sourceQa != null && !sourceQa.isBlank(), origin, "source_qa 가 없다");
        require(correlation != null, origin, "correlation 블록이 없다 — 버전 상관관계 키는 필수다");
        require(turns != null && !turns.isEmpty(), origin, "turns 가 비어 있다");
        for (Turn turn : turns) {
            String where = origin + " / " + (turn.name() == null ? "(이름 없는 턴)" : turn.name());
            require(turn.userMessage() != null && !turn.userMessage().isBlank(),
                    where, "user_message 가 없다");
            require(turn.expect() != null, where, "expect 블록이 없다");
            require(turn.expect().sseEvents() != null && !turn.expect().sseEvents().isEmpty(),
                    where, "expect.sse_events 가 없다");
            require(turn.expect().done() != null, where, "expect.done 블록이 없다");
            require(turn.expect().done().finishedReason() != null
                            && !turn.expect().done().finishedReason().isBlank(),
                    where, "expect.done.finished_reason 이 없다");
        }
    }

    private static void require(boolean condition, String origin, String message) {
        if (!condition) {
            throw new IllegalStateException("QA fixture 형식 오류 [" + origin + "]: " + message);
        }
    }

    Stubs stubsOrDefault(Turn turn) {
        return turn.stubs() != null ? turn.stubs() : Stubs.defaults();
    }
}
