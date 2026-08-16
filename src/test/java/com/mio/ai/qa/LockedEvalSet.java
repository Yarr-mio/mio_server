package com.mio.ai.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0-8 내부 잠금 평가셋 로더 (이슈 #454, 로드맵 §6.4·§11.3).
 *
 * <p><b>이 세트는 튜닝에 쓰지 않는다.</b> 로드맵 §6.1 이 적은 대로, 같은 데이터를 맞추는 데
 * 쓰고 다시 재는 데 쓰면 그 수치는 성능이 아니라 암기 결과다. 그래서 세트는 데이터로만
 * 존재하고({@code src/test/resources/eval/locked/}), 잠금은 문서가 아니라 두 개의 테스트가
 * 강제한다.
 *
 * <ul>
 *   <li>{@link LockedEvalSetIntegrityTest} — 매니페스트 해시로 조용한 수정을 잡는다.</li>
 *   <li>{@link LockedEvalContaminationGuardTest} — 케이스 본문이 프롬프트·템플릿·튜닝
 *       소스에 나타나면 실패한다. dev_gold 와의 중복·근사 중복도 여기서 막는다.</li>
 * </ul>
 *
 * <p>기존 172건 코퍼스({@link CrisisCorpus})는 이미 룰·프롬프트 튜닝에 노출됐으므로
 * <b>dev_gold</b> 로 남는다. 승격하지 않고, 잠금 세트가 그 케이스를 다시 쓰지도 않는다.
 *
 * <p>라벨 어휘는 {@code docs/eval/crisis-corpus-labeling-guide.md} 의 어휘를 그대로 쓴다
 * ({@code HARD_CRISIS/RISK/CLEAR}, 노출 등급). CBT·응답 품질 축은 그 어휘로 표현할 수 없어
 * {@code com.mio.ai.plan} 의 실제 값(ResponseAct, 금지 요소 코드)을 빌린다. 새 어휘 체계를
 * 따로 만들지 않는다.
 */
public final class LockedEvalSet {

    /** 데이터셋 버전. 케이스를 바꾸면 매니페스트와 함께 올린다. */
    public static final String VERSION = "mio-locked-eval-v1";

    static final String RESOURCE = "/eval/locked/mio-locked-eval-v1.json";
    static final String MANIFEST_RESOURCE = "/eval/locked/mio-locked-eval-v1.manifest.txt";

    /** 케이스 정규 문자열의 필드 구분자. {@code scripts/eval/locked_eval_manifest.py} 와 같아야 한다. */
    private static final String UNIT_SEPARATOR = "\u001f";
    private static final String RECORD_SEPARATOR = "\u001e";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] RAW = readResource(RESOURCE);
    private static final JsonNode ROOT = parse(RAW);

    public record Turn(String role, String text) {
        public boolean isUser() {
            return "USER".equals(role);
        }
    }

    /**
     * 케이스의 기대 판정.
     *
     * @param safetyTruth       라벨 지침 §2 어휘
     * @param exposure          라벨 지침 §4.2 노출 등급 — "무엇이 어떻게 전달됐는가"
     * @param responseAct       로드맵 §5.3 응답 행위. 아직 구현되지 않은 값도 포함한다
     * @param maxQuestions      허용 질문 수 상한
     * @param forbiddenElements 응답에 나타나면 안 되는 요소 코드
     */
    public record Expected(String safetyTruth, String exposure, String responseAct,
                           int maxQuestions, List<String> forbiddenElements) {
        public Expected {
            forbiddenElements = List.copyOf(forbiddenElements);
        }
    }

    /**
     * 잠금 케이스 한 건.
     *
     * @param pairKey            편향 축의 짝 식별자. 같은 짝의 변형들은 기대 판정이 같아야 하고
     *                           본문은 {@code variantToken} 하나만 달라야 한다. 편향 케이스가
     *                           아니면 빈 문자열이다
     * @param variantToken       최소대립쌍에서 바뀌는 표지 토큰. 이 토큰을 지운 나머지가 같은
     *                           짝끼리 글자까지 같아야 한다
     * @param deterministicLayer 모델 호출 이전(InputNormalizer·SafetyL1)에 결정되는 케이스.
     *                           전 셀이 같은 결과를 내므로 모델 변별 결과와 나눠 보고한다
     */
    public record LockedCase(String id, String subgroup, String axis, String pairKey,
                             String variantToken, boolean deterministicLayer,
                             List<Turn> turns, Expected expected, String rationale) {
        public LockedCase {
            turns = List.copyOf(turns);
        }

        public List<Turn> userTurns() {
            return turns.stream().filter(Turn::isUser).toList();
        }

        public boolean isMultiTurn() {
            return turns.size() > 1;
        }
    }

    /**
     * 데이터 권리 판정 (로드맵 §6.3).
     *
     * <p>산문이 아니라 값으로 남긴다. 평가 실행 manifest 가 이 판정을 그대로 실어야
     * "어떤 권리 조건의 데이터로 낸 수치인가" 가 실행 기록에서 사라지지 않는다.
     * PR #459 의 {@code EvalRunManifest} 는 {@link #asManifestFields()} 를 그대로 쓰면 된다.
     */
    public record DataRights(String sourceClass, String gateDecision, String gateReference,
                             boolean commercialEvaluationAllowed, boolean modelTrainingAllowed,
                             String redistribution, boolean containsRealUserData,
                             boolean containsPersonalData, boolean expertReviewed,
                             String expertReviewStatus) {

        /**
         * §6.3 판정을 실행 manifest 의 닫힌 어휘로 옮긴다.
         *
         * <p>값을 손으로 적지 않고 데이터에서 끌어온다. 데이터의 판정과 실행 기록의 판정이
         * 서로 다른 값을 말하면 권리 게이트가 기록 단계에서 무의미해지기 때문이다. 어휘 밖
         * 값이 들어오면 조용히 넘기지 않고 즉시 실패한다.
         */
        public EvalRunManifest.DataRights asManifestDataRights() {
            try {
                return EvalRunManifest.DataRights.valueOf(gateDecision);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "데이터의 gateDecision 이 §6.3 판정 어휘 밖이다: " + gateDecision, e);
            }
        }

        /**
         * 실행 manifest 의 {@code extra} 에 실을 평평한 키-값. 삽입 순서를 유지한다.
         *
         * <p>{@code dataset} 키는 넣지 않는다. {@link EvalRunManifest} 가 직접 기록하는 예약
         * 항목이라, 여기서 함께 내보내면 검증된 출처를 덮으려는 시도가 되어 manifest 생성이
         * 막힌다. 데이터셋 이름은 manifest 가 {@code datasetVersion} 으로 이미 싣는다.
         */
        public Map<String, String> asManifestFields() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("data_rights_source_class", sourceClass);
            fields.put("data_rights_gate_decision", gateDecision);
            fields.put("data_rights_gate_reference", gateReference);
            fields.put("data_rights_commercial_eval", String.valueOf(commercialEvaluationAllowed));
            fields.put("data_rights_model_training", String.valueOf(modelTrainingAllowed));
            fields.put("data_rights_redistribution", redistribution);
            fields.put("data_rights_real_user_data", String.valueOf(containsRealUserData));
            fields.put("data_rights_personal_data", String.valueOf(containsPersonalData));
            fields.put("data_rights_expert_review", expertReviewStatus);
            return fields;
        }
    }

    /**
     * 라벨링 현황 (로드맵 §11.3).
     *
     * <p>로드맵은 2인 독립 라벨과 3자 조정을 요구한다. 현재는 1인 라벨이고 이견률 측정값이
     * 없다. 그 사실을 값으로 남겨 실행 보고서가 없는 합의를 주장하지 못하게 한다.
     */
    public record LabelingStatus(int labelerCount, int independentLabelCount,
                                 int requiredIndependentLabelCount, boolean agreementMeasured,
                                 String clinicalValidation, String status) {

        public boolean meetsRoadmapRequirement() {
            return independentLabelCount >= requiredIndependentLabelCount && agreementMeasured;
        }

        public Map<String, String> asManifestFields() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("label_independent_count", String.valueOf(independentLabelCount));
            fields.put("label_required_independent_count",
                    String.valueOf(requiredIndependentLabelCount));
            fields.put("label_agreement_measured", String.valueOf(agreementMeasured));
            fields.put("label_clinical_validation", clinicalValidation);
            fields.put("label_status", status);
            return fields;
        }
    }

    /**
     * 보고 하한 (로드맵 §11.3, 리뷰 H2).
     *
     * <p>하위 그룹 n 이 작으면 관측 가능한 비율이 몇 개 값으로 양자화된다. n=12 면 한 건이
     * 뒤집힐 때 8.3%p 가 움직이므로 "셀 C 가 3인칭에서 나빠졌다" 같은 문장을 지지할 수 없다.
     * 그런데 계산해 두면 결국 인용되므로, <b>산출 자체를 금지하는 값</b>으로 둔다. 실행
     * 하네스는 이 값을 읽어 미달 그룹의 비율 필드를 비워야 한다.
     */
    public record Reporting(int minSubgroupN, String rule, String reportableUnit,
                            String deterministicLayerNote) {

        /** 이 그룹 크기로 비율을 보고해도 되는가. */
        public boolean isReportable(long subgroupSize) {
            return subgroupSize >= minSubgroupN;
        }

        public Map<String, String> asManifestFields() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("reporting_min_subgroup_n", String.valueOf(minSubgroupN));
            fields.put("reporting_unit", reportableUnit);
            return fields;
        }
    }

    public static final List<LockedCase> CASES = readCases();
    public static final DataRights DATA_RIGHTS = readDataRights();
    public static final LabelingStatus LABELING = readLabeling();
    public static final Reporting REPORTING = readReporting();

    /** 모델이 변별하는 케이스 — 결정론 계층이 이미 해결하는 케이스를 뺀 나머지. */
    public static List<LockedCase> modelDiscriminatingCases() {
        return CASES.stream().filter(c -> !c.deterministicLayer()).toList();
    }

    /** 모델 호출 이전에 결정되는 케이스. 전 셀이 같은 결과를 낸다. */
    public static List<LockedCase> deterministicLayerCases() {
        return CASES.stream().filter(LockedCase::deterministicLayer).toList();
    }

    /** 하위 그룹별 의도한 케이스 수. 실제 분포와 일치하는지는 무결성 테스트가 검사한다. */
    public static final Map<String, Integer> INTENDED_DISTRIBUTION = readDistribution();

    /** 라벨 어휘. 축별로 허용되는 값만 담는다. */
    public static final Map<String, List<String>> VOCABULARY = readVocabulary();

    /** 잠금 세트 파일의 원본 바이트 해시 — 공백 한 칸만 바뀌어도 값이 달라진다. */
    public static String fileSha256() {
        return hex(digest(RAW));
    }

    /** 매니페스트 원문. 파싱은 테스트가 한다 — 로더가 해석하면 검증과 데이터가 한 곳에 섞인다. */
    public static String manifestText() {
        return new String(readResource(MANIFEST_RESOURCE), StandardCharsets.UTF_8);
    }

    /**
     * 케이스 정규 문자열 (canonical v2).
     *
     * <p>{@code scripts/eval/locked_eval_manifest.py} 의 같은 이름 함수와 문자 단위로 같아야
     * 한다. JSON 직렬화 결과를 해시하지 않는 이유는 언어마다 키 순서·escape·공백 처리가
     * 달라 재현이 깨지기 때문이다. 필드를 명시적으로 이어붙이면 두 언어가 같은 값을 낸다.
     *
     * <p><b>v1 과의 차이</b>는 뒤에 {@code variantToken} 과 {@code deterministicLayer} 두 필드를
     * 더한 것이다. 두 값은 라벨과 같은 무게를 가진다 — 전자는 편향 최소대립쌍의 성립 근거이고,
     * 후자는 그 케이스의 결과를 모델 변별 지표에 넣을지 말지를 가른다. 해시 밖에 두면 잠금
     * 세트 안에 조용히 바꿀 수 있는 라벨성 필드가 남는다.
     */
    public static String canonicalForm(LockedCase c) {
        StringBuilder turns = new StringBuilder();
        for (int i = 0; i < c.turns().size(); i++) {
            if (i > 0) {
                turns.append(RECORD_SEPARATOR);
            }
            turns.append(c.turns().get(i).role()).append(':').append(c.turns().get(i).text());
        }
        String expected = String.join("|",
                c.expected().safetyTruth(),
                c.expected().exposure(),
                c.expected().responseAct(),
                String.valueOf(c.expected().maxQuestions()),
                String.join(",", c.expected().forbiddenElements()));
        return String.join(UNIT_SEPARATOR,
                c.id(), c.subgroup(), c.axis(), c.pairKey(), turns.toString(), expected,
                c.rationale(), c.variantToken(), c.deterministicLayer() ? "1" : "0");
    }

    public static String caseSha256(LockedCase c) {
        return hex(digest(canonicalForm(c).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 비교용 정규화.
     *
     * <p>NFKC + 소문자 + 결합 문자 제거 + <b>서식 제어문자(제로폭·양방향) 제거</b> + 공백
     * 제거까지만 한다. <b>구두점과 기호는 남긴다</b> — 표기 우회 케이스는 구분자가 곧 케이스의
     * 내용이라, 그것까지 지우면 서로 다른 우회 형태가 같은 문자열로 뭉개진다.
     *
     * <p>제로폭 공백(U+200B)·양방향 제어문자(U+202E 등) 같은 Unicode FORMAT 범주를 지우는
     * 이유는 세트 자신의 위협 모형과 같다. {@code SAFE-자모기호우회} 하위 그룹이 다루는 표기
     * 우회와 정확히 같은 부류로, 눈에 보이지 않는 문자 하나를 끼워 넣으면 조각의 연속성이
     * 끊겨 문자열 검사가 통째로 무력화된다. {@link LockedEvalContaminationSelfTest} 가 그
     * 우회를 심어 두고 매번 확인한다.
     *
     * <p><b>이 함수는 해시에 관여하지 않는다.</b> 케이스 정규 문자열({@link #canonicalForm})은
     * 원문을 그대로 이어 붙이므로, 여기 규칙을 바꿔도 매니페스트 해시는 변하지 않는다. 즉
     * {@code scripts/eval/locked_eval_manifest.py} 와의 동등성은 이 변경과 무관하다.
     */
    public static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase();
        StringBuilder out = new StringBuilder(decomposed.length());
        decomposed.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.NON_SPACING_MARK
                    || Character.getType(cp) == Character.COMBINING_SPACING_MARK
                    || Character.getType(cp) == Character.ENCLOSING_MARK
                    || Character.getType(cp) == Character.FORMAT
                    || Character.isWhitespace(cp)) {
                return;
            }
            out.appendCodePoint(cp);
        });
        return out.toString();
    }

    /** 3-gram Jaccard 유사도. 근사 중복 판정에 쓴다. */
    public static double similarity(String a, String b) {
        var ta = trigrams(a);
        var tb = trigrams(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        long intersection = ta.stream().filter(tb::contains).count();
        long union = ta.size() + tb.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static java.util.Set<String> trigrams(String s) {
        var out = new java.util.LinkedHashSet<String>();
        if (s.length() < 3) {
            if (!s.isEmpty()) {
                out.add(s);
            }
            return out;
        }
        for (int i = 0; i + 3 <= s.length(); i++) {
            out.add(s.substring(i, i + 3));
        }
        return out;
    }

    // ── 로딩 ────────────────────────────────────────────────────────

    private static List<LockedCase> readCases() {
        List<LockedCase> cases = new ArrayList<>();
        for (JsonNode node : ROOT.get("cases")) {
            List<Turn> turns = new ArrayList<>();
            for (JsonNode turn : node.get("turns")) {
                turns.add(new Turn(turn.get("role").asText(), turn.get("text").asText()));
            }
            JsonNode e = node.get("expected");
            List<String> forbidden = new ArrayList<>();
            e.get("forbiddenElements").forEach(f -> forbidden.add(f.asText()));
            cases.add(new LockedCase(
                    node.get("id").asText(),
                    node.get("subgroup").asText(),
                    node.get("axis").asText(),
                    node.has("pairKey") ? node.get("pairKey").asText() : "",
                    node.has("variantToken") ? node.get("variantToken").asText() : "",
                    node.has("deterministicLayer") && node.get("deterministicLayer").asBoolean(),
                    turns,
                    new Expected(e.get("safetyTruth").asText(), e.get("exposure").asText(),
                            e.get("responseAct").asText(), e.get("maxQuestions").asInt(), forbidden),
                    node.get("rationale").asText()));
        }
        return List.copyOf(cases);
    }

    private static DataRights readDataRights() {
        JsonNode n = ROOT.get("dataRights");
        return new DataRights(
                n.get("sourceClass").asText(),
                n.get("gateDecision").asText(),
                n.get("gateReference").asText(),
                n.get("commercialEvaluationAllowed").asBoolean(),
                n.get("modelTrainingAllowed").asBoolean(),
                n.get("redistribution").asText(),
                n.get("containsRealUserData").asBoolean(),
                n.get("containsPersonalData").asBoolean(),
                n.get("expertReviewed").asBoolean(),
                n.get("expertReviewStatus").asText());
    }

    private static LabelingStatus readLabeling() {
        JsonNode n = ROOT.get("labeling");
        return new LabelingStatus(
                n.get("labelerCount").asInt(),
                n.get("independentLabelCount").asInt(),
                n.get("requiredIndependentLabelCount").asInt(),
                n.get("agreementMeasured").asBoolean(),
                n.get("clinicalValidation").asText(),
                n.get("status").asText());
    }

    private static Reporting readReporting() {
        JsonNode n = ROOT.get("reporting");
        return new Reporting(
                n.get("minSubgroupN").asInt(),
                n.get("rule").asText(),
                n.get("reportableUnit").asText(),
                n.get("deterministicLayerNote").asText());
    }

    private static Map<String, Integer> readDistribution() {
        Map<String, Integer> out = new LinkedHashMap<>();
        ROOT.get("distribution").fields()
                .forEachRemaining(e -> out.put(e.getKey(), e.getValue().asInt()));
        return Map.copyOf(out);
    }

    private static Map<String, List<String>> readVocabulary() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        ROOT.get("labelVocabulary").fields().forEachRemaining(e -> {
            if (!e.getValue().isArray()) {
                return;
            }
            List<String> values = new ArrayList<>();
            e.getValue().forEach(v -> values.add(v.asText()));
            out.put(e.getKey(), List.copyOf(values));
        });
        return Map.copyOf(out);
    }

    private static byte[] readResource(String name) {
        try (InputStream in = LockedEvalSet.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("잠금 평가셋 리소스를 찾지 못했다: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("잠금 평가셋을 읽지 못했다: " + name, e);
        }
    }

    private static JsonNode parse(byte[] raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (IOException e) {
            throw new UncheckedIOException("잠금 평가셋 JSON 파싱 실패", e);
        }
    }

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private LockedEvalSet() {
    }
}
