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
        DatasetSplit datasetSplit,
        int datasetSize,
        String labelGuide,
        DataRights dataRights,
        TuningExposure tuningExposure,
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
     * 평가셋의 성격 (로드맵 §6.4).
     *
     * <p>평문 문자열이면 오타가 곧 새 split 이 되어 셀 비교의 분모가 흔들린다. 값이 닫혀 있어야
     * "같은 split 을 돌렸는가" 를 기계적으로 물을 수 있다.
     */
    enum DatasetSplit {
        /** 튜닝에 한 번도 노출되지 않은 잠금 gold. Go/No-Go 판정의 기준셋이다. */
        LOCKED_GOLD("locked_gold"),
        /** 튜닝·회귀에 이미 쓰인 개발용 gold. 개선 방향은 볼 수 있지만 최종 판정에는 못 쓴다. */
        DEV_GOLD("dev_gold"),
        /** 상위 모델(teacher)이 라벨을 붙인 대량 silver. */
        TEACHER_SILVER("teacher_silver"),
        /** 우회·공격 패턴을 합성한 적대적 세트. */
        ADVERSARIAL_SYNTHETIC("adversarial_synthetic");

        private final String value;

        DatasetSplit(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    /**
     * 이 평가셋이 튜닝에 노출된 적 있는지에 대한 진술 (로드맵 §6.4).
     *
     * <p>잠금 gold 의 값은 "튜닝에 노출된 적 없다" 는 사실 하나에서 나온다. 그 사실을 관례로만
     * 지키면, 이미 룰·프롬프트 튜닝에 쓴 데이터셋을 {@code locked_gold} 로 적어도 아무것도
     * 막지 않는다 — 오염된 세트로 낸 수치는 성능이 아니라 암기 결과인데, 기록만 보고는 둘을
     * 구별할 수 없다.
     *
     * <p><b>설계 선택</b>: 별도 레지스트리 조회 대신 <b>필수 동반 필드</b>로 둔다. 레지스트리는
     * 그 자체가 최신인지 다시 확인해야 하는 또 하나의 상태이고, 잠금셋이 아직 만들어지지 않은
     * 지금은 유지 비용만 남는다. 필수 필드는 잠금이라고 적는 사람이 같은 커밋에서 노출 이력을
     * 명시적으로 진술하게 만들고, 진술이 없으면 manifest 자체가 만들어지지 않는다.
     */
    enum TuningExposure {
        /** 룰·프롬프트 튜닝, few-shot 예시 어디에도 쓰인 적 없음. 잠금 표기의 전제. */
        NEVER_USED("튜닝·프롬프트 피팅·few-shot 에 사용된 적 없음"),
        /** 하네스 수치·프롬프트 튜닝에 이미 쓰임. */
        USED_FOR_TUNING("룰·프롬프트 튜닝에 사용됨"),
        /** 노출 이력을 확인하지 못함. 모른다는 것은 깨끗하다는 뜻이 아니다. */
        UNVERIFIED("튜닝 노출 이력 미확인");

        private final String attestation;

        TuningExposure(String attestation) {
            this.attestation = attestation;
        }

        String attestation() {
            return attestation;
        }
    }

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
            "data_rights", "tuning_exposure",
            "prompt_version", "policy_version", "pricing_as_of", "random_seed", "command");

    /** 역할·게이트가 늘어나면 키도 늘어나므로 이름이 아니라 네임스페이스 단위로 예약한다. */
    private static final List<String> RESERVED_PREFIXES = List.of("model.", "gate_");

    EvalRunManifest {
        requireText("scope", scope);
        requireText("cell", cell);
        requireText("dataset_version", datasetVersion);
        requireText("label_guide", labelGuide);
        requireText("prompt_version", promptVersion);
        requireText("policy_version", policyVersion);
        requireText("pricing_as_of", pricingAsOf);
        requireText("random_seed", randomSeed);
        requireText("command", command);
        if (datasetSplit == null) {
            throw new IllegalArgumentException(
                    "dataset_split 이 비었다 — 어떤 성격의 평가셋인지 모르는 수치는 셀 비교에 쓸 수 없다");
        }
        if (tuningExposure == null) {
            throw new IllegalArgumentException(
                    "tuning_exposure 가 비었다 — 튜닝 노출 이력이 없는 기록은 잠금 여부를 확인할 수 없다");
        }
        if (datasetSplit == DatasetSplit.LOCKED_GOLD && tuningExposure != TuningExposure.NEVER_USED) {
            throw new IllegalArgumentException(
                    "locked_gold 인데 튜닝 미노출 진술이 없다 (" + tuningExposure.attestation() + ") — "
                            + "튜닝에 노출된 세트의 수치는 성능이 아니라 암기 결과다");
        }
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
        metadata.put("dataset_split", datasetSplit.value());
        metadata.put("dataset_size", String.valueOf(datasetSize));
        metadata.put("label_guide", labelGuide);
        metadata.put("data_rights", dataRights.judgement());
        metadata.put("tuning_exposure", tuningExposure.attestation());
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
