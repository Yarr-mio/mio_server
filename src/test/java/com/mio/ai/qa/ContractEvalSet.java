package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.qa.LockedEvalSet.Expected;
import com.mio.ai.qa.LockedEvalSet.LockedCase;
import com.mio.ai.qa.LockedEvalSet.Turn;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계약 준수 실측용 <b>개발 gold</b> (이슈 #305, 로드맵 §5.8).
 *
 * <h2>왜 잠금 gold 를 쓰지 않는가</h2>
 *
 * <p>두 가지 이유가 각각 단독으로 결정적이다.
 *
 * <p><b>1. 용도가 금지돼 있다.</b> 잠금 세트는 {@code lock.forbiddenUses} 첫 항목으로
 * "프롬프트 튜닝" 을 적었다. 이 평가가 답하려는 물음 — "{@code [응답 계약]} 블록이 위반율을
 * 실제로 낮추는가" — 은 프롬프트를 유지·삭제·수정할지 정하는 근거다. 그 근거로 쓰는 순간
 * 잠금 세트는 프롬프트 피팅에 노출되고, {@code EvalRunManifest} 가 요구하는
 * {@code NEVER_USED} 진술이 거짓이 된다. 한 번 거짓이 되면 그 세트로 낸 <b>과거·미래의 모든</b>
 * Go/No-Go 수치가 같이 무너진다.
 *
 * <p><b>2. 모집단이 없다.</b> P0-8 3단계 전량 실행(잠금 323건, 모델 변별 301건)에서 계약 적용
 * 턴은 <b>12건</b>이었다. {@code minSubgroupN=30} 미달이라 세 실행 모두 "계약 위반율 미보고" 로
 * 찍혔고, 인용할 수 있는 것은 건수뿐이었다. 잠금 세트는 안전 <b>탐지</b>를 재도록 설계됐고,
 * 그 설계상 대부분의 턴이 위기 고정 플로우이거나 계획 범위 밖이다.
 *
 * <h2>이 세트가 모집단을 만드는 방법</h2>
 *
 * <p>{@code ResponsePlanner} 가 계약을 거는 조건은 좁고 결정론적이다 — {@code GENERATE} 이면서
 * 위험도 HIGH/MEDIUM 이거나 생성 모드가 SUPPORTIVE 인 턴. 그 셋은 모두 <b>룰 레이어가
 * InputJudge 로 올린 턴</b>에서만 나온다({@code CombinedSignal.requiresJudge}). 반대로 룰이
 * 위기·보안 공격으로 확정한 턴은 고정 응답이라 계약 대상이 아니다.
 *
 * <p>그래서 이 세트는 <b>"룰이 Judge 로 올리되 위기·보안으로 확정하지 않는 턴"</b> 만 모은다.
 * 그 조건은 모델을 부르지 않고 검사할 수 있고, {@link ContractEvalSetTest} 가 합성
 * {@code InputJudgeResult} 로 <b>실제</b> {@code PolicyEngine}·{@code ResponsePlanner} 를
 * 등급 × 보안 판정 전 조합에 통과시켜 확인한다.
 *
 * <p><b>보장은 무조건이 아니라 "Judge 판정 modulo" 다.</b> 이탈이 정확히 둘 있고 둘 다 Judge 가
 * 내리는 판정이라 무과금으로 닫을 수 없다 — (1) Judge 가 {@code HARD_CRISIS} 로 올리는 경우,
 * (2) Judge 자신의 보안 판정이 non-CLEAN 이라 {@code EffectiveSecurityResolver} 가
 * {@code SUSPICIOUS} 로 올리고 등급이 {@code LOW} 이하인 경우. 실행이 각각
 * {@code crisis_routed}·{@code unplanned_turns} 로 세어 보고하며, <b>이름 없는 세 번째 이탈이
 * 생기면 테스트가 실패한다.</b>
 *
 * <p><b>행위별</b> 분포는 그래도 실행 전에 확정할 수 없다 — 어느 행위가 계획되는지는 Judge
 * 판정이 정하기 때문이다. {@code expected.responseAct} 는 정답 라벨이 아니라 <b>설계 의도</b>이며,
 * 보고는 관측된 분포를 쓰고 하한 미달 행위는 {@link ReportableRate} 가 비율을 막는다.
 */
final class ContractEvalSet {

    static final String VERSION = "mio-contract-eval-v1";
    static final String RESOURCE = "/eval/contract/mio-contract-eval-v1.json";
    static final String LABEL_GUIDE = "docs/eval/contract-compliance.md";

    /** 계약 검사가 실제로 걸리는 세 가지 응답 행위. 그 밖의 계획은 검사 대상이 아니다. */
    static final List<ResponseAct> CONTRACT_ACTS = List.of(
            ResponseAct.EMPATHIC_REFLECTION, ResponseAct.EMOTION_CHECK, ResponseAct.CLARIFY_CONTEXT);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] RAW = readResource();
    private static final JsonNode ROOT = parse(RAW);

    static final List<LockedCase> CASES = readCases();
    static final LockedEvalSet.DataRights DATA_RIGHTS = readDataRights();
    static final LockedEvalSet.LabelingStatus LABELING = readLabeling();

    private ContractEvalSet() {
    }

    /**
     * 튜닝 노출 진술 (로드맵 §6.4).
     *
     * <p>이 세트의 결과는 프롬프트 결정의 근거가 된다. 첫 실행 시점에 아직 쓰인 적이 없다는
     * 이유로 {@code NEVER_USED} 를 적으면, 나중에 그 표기를 보고 이 수치를 릴리스 판정에
     * 인용하게 된다. 용도가 튜닝이면 처음부터 튜닝 노출로 적는다 — 그것이 잠금 세트의
     * {@code NEVER_USED} 를 진짜로 지키는 방법이기도 하다.
     */
    static EvalRunManifest.TuningExposure tuningExposure() {
        return EvalRunManifest.TuningExposure.valueOf(text(ROOT.path("tuningExposure"), "value"));
    }

    static String tuningExposureReason() {
        return text(ROOT.path("tuningExposure"), "reason");
    }

    static String lockState() {
        return text(ROOT.path("lock"), "state");
    }

    static String purpose() {
        return text(ROOT, "purpose");
    }

    /** 파일 원본 바이트 해시. 케이스가 한 글자만 바뀌어도 값이 달라진다. */
    static String fileSha256() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(RAW);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }

    /** 하위 그룹별 의도한 케이스 수. 실제 분포와 어긋나면 무결성 테스트가 잡는다. */
    static Map<String, Integer> intendedDistribution() {
        Map<String, Integer> out = new LinkedHashMap<>();
        ROOT.path("distribution").fields()
                .forEachRemaining(e -> out.put(e.getKey(), e.getValue().asInt()));
        return Map.copyOf(out);
    }

    /** 실행 manifest 의 {@code extra} 에 실을 세트 출처 항목. */
    static Map<String, String> manifestFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.putAll(DATA_RIGHTS.asManifestFields());
        fields.putAll(LABELING.asManifestFields());
        fields.put("contract_set_sha256", fileSha256());
        fields.put("contract_set_lock_state", lockState());
        fields.put("tuning_exposure_reason", tuningExposureReason());
        return fields;
    }

    // ── 로딩 ──────────────────────────────────────────────────────

    private static List<LockedCase> readCases() {
        List<LockedCase> cases = new ArrayList<>();
        for (JsonNode node : ROOT.path("cases")) {
            List<Turn> turns = new ArrayList<>();
            for (JsonNode turn : node.path("turns")) {
                turns.add(new Turn(text(turn, "role"), text(turn, "text")));
            }
            JsonNode expected = node.path("expected");
            List<String> forbidden = new ArrayList<>();
            expected.path("forbiddenElements").forEach(v -> forbidden.add(v.asText()));
            cases.add(new LockedCase(
                    text(node, "id"), text(node, "subgroup"), text(node, "axis"),
                    "", "", false, turns,
                    new Expected(text(expected, "safetyTruth"), text(expected, "exposure"),
                            text(expected, "responseAct"), expected.path("maxQuestions").asInt(),
                            List.copyOf(forbidden)),
                    node.path("rationale").asText("")));
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("계약 평가셋이 비었다: " + RESOURCE);
        }
        return List.copyOf(cases);
    }

    private static LockedEvalSet.DataRights readDataRights() {
        JsonNode node = ROOT.path("dataRights");
        return new LockedEvalSet.DataRights(
                text(node, "sourceClass"), text(node, "gateDecision"), text(node, "gateReference"),
                node.path("commercialEvaluationAllowed").asBoolean(),
                node.path("modelTrainingAllowed").asBoolean(),
                text(node, "redistribution"),
                node.path("containsRealUserData").asBoolean(),
                node.path("containsPersonalData").asBoolean(),
                node.path("expertReviewed").asBoolean(),
                text(node, "expertReviewStatus"));
    }

    private static LockedEvalSet.LabelingStatus readLabeling() {
        JsonNode node = ROOT.path("labeling");
        return new LockedEvalSet.LabelingStatus(
                node.path("labelerCount").asInt(),
                node.path("independentLabelCount").asInt(),
                node.path("requiredIndependentLabelCount").asInt(),
                node.path("agreementMeasured").asBoolean(),
                text(node, "clinicalValidation"), text(node, "status"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "계약 평가셋에 '%s' 가 없다 — 출처가 빈 세트는 실행에 쓰지 않는다".formatted(field));
        }
        return value.asText();
    }

    private static byte[] readResource() {
        try (InputStream in = ContractEvalSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("계약 평가셋을 찾을 수 없다: " + RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("계약 평가셋을 읽지 못했다: " + RESOURCE, e);
        }
    }

    private static JsonNode parse(byte[] raw) {
        try {
            return MAPPER.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("계약 평가셋 JSON 을 파싱하지 못했다", e);
        }
    }
}
