package com.mio.ai.qa;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 평가 실행 1회의 출처를 빠짐없이 고정하는 manifest (로드맵 §10.5, §11.2 / P0-8).
 *
 * <p>기존 아카이브는 {@code Map<String, String>} 자유 형식이라, 무엇을 남길지가 호출부의
 * 재량이었다. 그 결과 저장소에 남은 실행 기록에 <b>프롬프트 버전·random seed·실제 model
 * ID·단가 기준일이 통째로 빠져 있다.</b> 빠졌다는 사실조차 파일을 열어봐야 알 수 있었다.
 *
 * <p>A~E 셀 비교는 "같은 split 을 같은 코드·프롬프트·정책 버전으로 돌렸다" 는 전제 위에서만
 * 의미가 있다. 그 전제를 확인할 수 없는 실행으로 Go/No-Go 를 내리는 것은, 판정을 하지 않는
 * 것보다 나쁘다 — 근거가 없다는 사실이 근거가 있다는 외양에 가려지기 때문이다. 그래서 이
 * 타입은 필수 항목이 비면 <b>기록을 남기지 않고 즉시 실패</b>한다.
 *
 * <p>아직 저장소에 개념이 없는 항목(프롬프트 버전)은 조용히 생략하는 대신 {@link #UNVERSIONED}
 * 같은 명시적 표식을 넣게 한다. 모든 실행 기록에 "없음" 이 찍히는 편이, 항목 자체가 사라져
 * 나중에 있었는지 없었는지 알 수 없게 되는 것보다 낫다.
 */
record EvalRunManifest(
        String scope,
        String cell,
        String datasetVersion,
        String datasetSplit,
        int datasetSize,
        String labelGuide,
        DataRights dataRights,
        Map<String, String> models,
        String promptVersion,
        String policyVersion,
        String pricingAsOf,
        String randomSeed,
        String command,
        Map<String, String> gates,
        Map<String, String> extra) {

    /** 저장소에 프롬프트 버전 체계가 없음을 실행 기록에 남기기 위한 표식. */
    static final String UNVERSIONED = "unversioned (프롬프트 버전 체계 미구현 — #454)";

    /** 시드가 결과에 영향을 주지 않는 실행(전수 평가 등). "안 남겼다" 와 구별하기 위한 값. */
    static final String NO_SEED = "n/a (전수 평가 — 표본 추출 없음)";

    /** A~E 셀 비교가 아닌 단독 기준선 실행. */
    static final String BASELINE_CELL = "A (현행 운영 기준선)";

    /**
     * {@code openai.pricing} 에 기준일 필드가 없다. 단가가 바뀌면 과거 실행의 "수용 응답당
     * 원가" 를 어느 시점 가격으로 계산한 값인지 복원할 수 없다 — 설정에 기준일을 넣는 것이
     * 정답이지만, 그건 설정 스키마 변경이라 별도 작업으로 둔다.
     */
    static final String PRICING_DATE_UNRECORDED = "application.yml openai.pricing (기준일 미기재 — #454)";

    /** 모델을 한 건도 호출하지 않는 실행(룰 레이어 단독)에서 역할 값으로 쓴다. */
    static final String NOT_CALLED = "n/a (미호출)";

    /**
     * 평가에 쓴 데이터셋의 권리 판정 (로드맵 §6.3 / 이슈 #454 "데이터 권리 게이트 통과 기록").
     *
     * <p>자유 문자열이면 호출부마다 다른 표현을 쓰게 되고, 그러면 여러 실행 기록을 놓고
     * "권리가 확인된 데이터로만 낸 수치인가" 를 기계적으로 물을 수 없다. 판정 어휘는 §6.3
     * 판정표에 이미 고정돼 있으므로 그대로 타입으로 옮긴다.
     */
    enum DataRights {
        /** 자체 보유·자체 작성 데이터. 별도 확인 없이 쓸 수 있다. */
        PRIORITY_USE("우선 사용"),
        /** 라이선스 조건(출처 표기·비상업 등)을 지키는 범위에서만 쓸 수 있다. */
        CONDITIONAL("조건부 사용 가능"),
        /** 권리 확인이 끝나지 않은 후보. 확인 전 수치는 잠정값으로만 읽어야 한다. */
        PENDING_VERIFICATION("권리 확인 후 후보"),
        /** 판정 결과 사용 불가. 이 값으로는 실행 기록을 만들 수 없다. */
        EXCLUDED("제외");

        private final String judgement;

        DataRights(String judgement) {
            this.judgement = judgement;
        }

        /** 실행 기록에 실릴 표현. 판정 어휘와 근거 조항을 한 칸에 같이 남긴다. */
        String judgement() {
            return judgement + " (로드맵 §6.3 판정)";
        }
    }

    /**
     * manifest 가 직접 기록하는 항목 이름. {@code extra} 가 이 이름을 쓰면 생성 자체를 막는다.
     *
     * <p>{@code run_at}·{@code code_commit} 은 {@link EvalRunArchive} 가 헤더에 먼저 넣는
     * 값이라 여기 함께 둔다 — 실행 시각과 코드 리비전이 덮이면 기록의 의미가 통째로 사라진다.
     */
    private static final Set<String> RESERVED_KEYS = Set.of(
            "run_at", "code_commit",
            "scope", "cell", "dataset", "dataset_split", "dataset_size", "label_guide",
            "data_rights",
            "prompt_version", "policy_version", "pricing_as_of", "random_seed", "command");

    /** 역할·게이트가 늘어나면 키도 늘어나므로 이름이 아니라 네임스페이스 단위로 예약한다. */
    private static final List<String> RESERVED_PREFIXES = List.of("model.", "gate_");

    EvalRunManifest {
        requireText("scope", scope);
        requireText("cell", cell);
        requireText("dataset_version", datasetVersion);
        requireText("dataset_split", datasetSplit);
        requireText("label_guide", labelGuide);
        requireText("prompt_version", promptVersion);
        requireText("policy_version", policyVersion);
        requireText("pricing_as_of", pricingAsOf);
        requireText("random_seed", randomSeed);
        requireText("command", command);
        if (dataRights == null) {
            throw new IllegalArgumentException(
                    "data_rights 가 비었다 — 권리 판정이 없는 데이터로 낸 수치는 확인된 수치와 구별할 수 없다");
        }
        if (dataRights == DataRights.EXCLUDED) {
            // "제외" 라고 정직하게 적는 것은 위반을 문서화할 뿐 되돌리지 못한다. 기록을 막아
            // 실행을 막는 편이 게이트에 가깝다.
            throw new IllegalArgumentException(
                    "data_rights 판정이 제외다 — §6.3 에서 사용 불가로 판정된 데이터의 실행 기록은 남기지 않는다");
        }
        if (datasetSize <= 0) {
            throw new IllegalArgumentException("dataset_size 가 0 이하다 — 빈 평가셋의 실행 기록은 남기지 않는다");
        }
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException(
                    "models 가 비었다 — 어떤 모델이 낸 수치인지 모르는 실행은 셀 비교에 쓸 수 없다");
        }
        models.forEach((role, id) -> {
            // 역할 이름이 비면 "model." 이라는 이름 없는 행이 남는다 — 누가 낸 수치인지 모르는
            // 행은 없느니만 못하므로 값과 같은 기준으로 막는다.
            requireText("models 의 역할 이름", role);
            requireText("models." + role, id);
        });
        // extra 는 자유 영역이지만, 자유가 검증된 항목을 덮는 데까지 미치면 manifest 와 기록물이
        // 다른 값을 말하게 된다. 기록 시점이 아니라 생성 시점에 막아 어긋난 manifest 자체를
        // 만들 수 없게 한다 — 이 타입의 다른 검증과 같은 fail-closed 원칙이다.
        if (extra != null) {
            extra.forEach((key, value) -> {
                requireText("extra 의 키", key);
                requireText("extra." + key, value);
                requireNotReserved(key);
            });
        }
        // 방어적 복사: 호출부가 나중에 맵을 바꿔도 기록된 manifest 는 변하지 않는다.
        models = Map.copyOf(models);
        gates = gates == null ? Map.of() : Map.copyOf(gates);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /**
     * 아카이브 헤더에 실릴 순서 있는 표현.
     *
     * <p>출처(무엇을 어떤 버전으로 돌렸나) → 게이트(무엇을 통과 기준으로 삼았나) →
     * 부가 정보 순으로 고정한다. 사람이 여러 실행을 나란히 놓고 읽을 때 같은 행이 같은
     * 자리에 있어야 차이가 눈에 들어온다.
     */
    Map<String, String> toMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("scope", scope);
        metadata.put("cell", cell);
        metadata.put("dataset", datasetVersion);
        metadata.put("dataset_split", datasetSplit);
        metadata.put("dataset_size", String.valueOf(datasetSize));
        metadata.put("label_guide", labelGuide);
        metadata.put("data_rights", dataRights.judgement());
        // 역할이 늘어도 행 순서가 흔들리지 않게 정렬해서 싣는다.
        new TreeMap<>(models).forEach((role, id) -> metadata.put("model." + role, id));
        metadata.put("prompt_version", promptVersion);
        metadata.put("policy_version", policyVersion);
        metadata.put("pricing_as_of", pricingAsOf);
        metadata.put("random_seed", randomSeed);
        new TreeMap<>(gates).forEach((name, value) -> metadata.put("gate_" + name, value));
        // 예약 키는 생성자가 이미 막았으므로 여기서 기존 항목이 덮일 일은 없다.
        metadata.putAll(new TreeMap<>(extra));
        metadata.put("command", command);
        return metadata;
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " 가 비었다 — 출처를 복원할 수 없는 실행 기록은 남기지 않는다");
        }
    }

    private static void requireNotReserved(String key) {
        boolean reserved = RESERVED_KEYS.contains(key)
                || RESERVED_PREFIXES.stream().anyMatch(key::startsWith);
        if (reserved) {
            throw new IllegalArgumentException(
                    "extra 의 키 '" + key + "' 는 manifest 가 직접 기록하는 예약 항목이다 — "
                            + "검증된 출처가 조용히 덮인 기록은 남기지 않는다");
        }
    }
}
