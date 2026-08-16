package com.mio.ai.qa;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 로드맵 §11.3 의 A~E 셀 정의.
 *
 * <p>표의 다섯 행을 <b>값</b>으로 옮긴 것이다. 이 파일에는 모델 ID 가 하나도 없다 — 셀은
 * "어떤 역할을 어느 등급의 모델로 채우고, 하네스에서 무엇을 켜고 끄는가" 만 말하고, 실제
 * ID 는 {@link CellModelRegistry} 가 실행 직전에 핀한다. 로드맵이 "정확한 공급자·모델 ID 는
 * 가격과 가용성이 바뀌므로 코드 상수나 문서의 영구 결론으로 고정하지 않는다" 고 못박은
 * 이유가 그대로다.
 *
 * <p>셀을 늘리려면 여기 상수를 하나 더하고 필요한 역할과 하네스 스위치를 적으면 된다.
 * 실행 코드({@link CellRunner})는 이 값만 읽는다.
 */
enum BenchmarkCell {

    /**
     * A 기준선 — 현재 운영 모델 + 현재 룰 + mini Judge + holdback.
     *
     * <p>비교의 분모다. 다른 셀의 모든 수치는 이 셀 대비로만 의미가 있다.
     */
    A("A 기준선", "현재 운영 모델", "현재 룰 + mini Judge + holdback", "비교 기준",
            Map.of(CellModelRole.GENERATION, ModelTier.OPERATIONAL,
                    CellModelRole.INPUT_SAFETY, ModelTier.OPERATIONAL,
                    CellModelRole.OUTPUT_JUDGE, ModelTier.OPERATIONAL,
                    CellModelRole.CBT_CLASSIFIER, ModelTier.OPERATIONAL),
            HarnessShape.CURRENT),

    /** B 상위 생성 — 상위 모델 + 현재 하네스. 모델 자체의 품질 상승 폭과 비용을 본다. */
    B("B 상위 생성", "상위 모델", "현재 하네스", "모델 자체의 품질 상승 폭과 비용",
            Map.of(CellModelRole.GENERATION, ModelTier.FRONTIER,
                    CellModelRole.INPUT_SAFETY, ModelTier.OPERATIONAL,
                    CellModelRole.OUTPUT_JUDGE, ModelTier.OPERATIONAL,
                    CellModelRole.CBT_CLASSIFIER, ModelTier.OPERATIONAL),
            HarnessShape.CURRENT),

    /**
     * C 상위 teacher — 운영 경로는 A 와 같고, 상위 모델은 offline reference 로만 쓴다.
     *
     * <h2>이 셀이 실제로 무엇을 하는가</h2>
     *
     * <p>온라인 경로는 셀 A 와 <b>같은 역할·같은 모델</b>로 돈다. 그 뒤에 별도 pass 로
     * {@link CellModelRole#REFERENCE_JUDGE} 가 같은 케이스를 다시 채점한다
     * ({@link CellReferenceReview}). 이 pass 는 자기 원장·자기 클라이언트를 쓰고, 온라인
     * 결과가 모두 확정된 뒤에 돌기 때문에 턴당 원가·지연에 <b>구조적으로</b> 들어갈 수 없다.
     * 산출물은 "gold 라벨과의 이견률" 과 "온라인 등급이 놓친 것을 reference 가 잡아낸 건수"
     * 이며, 둘 다 온라인 지표와 <b>분리된 절</b>로만 보고된다.
     *
     * <h2>A==C 로 무엇을 단언하고 무엇을 단언하지 않는가</h2>
     *
     * <p>{@link CellParity} 가 단언하는 것은 <b>구성의 동일성</b>이다 — 온라인 역할별 모델이
     * A 와 같고, 온라인 원장에 {@link CellModelRole#OFFLINE_COMPONENT} 태그 호출이 0건이다.
     * 턴당 원가·p95 의 <b>수치 동일성은 단언하지 않는다</b>: 같은 모델이라도 샘플링 때문에
     * completion 토큰과 OutputJudge 발화 횟수가 달라지므로 정확히 같을 수 없다. 대신 그
     * 차이를 오염 <b>신호</b>로 리포트에 찍고, 구성이 같은데 차이가 크면 사람이 본다.
     */
    C("C 상위 teacher", "현재 운영 모델", "상위 모델은 offline reference 만",
            "운영비 증가 없이 오류 발견·라벨 품질 개선 여부",
            Map.of(CellModelRole.GENERATION, ModelTier.OPERATIONAL,
                    CellModelRole.INPUT_SAFETY, ModelTier.OPERATIONAL,
                    CellModelRole.OUTPUT_JUDGE, ModelTier.OPERATIONAL,
                    CellModelRole.CBT_CLASSIFIER, ModelTier.OPERATIONAL,
                    CellModelRole.REFERENCE_JUDGE, ModelTier.FRONTIER),
            HarnessShape.CURRENT),

    /**
     * D 경량 cascade — 룰·계약 우선, mini 조건부, 상위 모델은 난례만.
     *
     * <p>로드맵이 경고한 대로 "작은 모델로 일괄 교체" 가 아니다. 바꾸는 것은 하네스의
     * 모양이고({@link HarnessShape#RULE_FIRST_CASCADE}), 생성은 경량 등급으로 내린다.
     */
    D("D 경량 cascade", "현재/경량 모델", "룰·계약 우선, mini 조건부, 상위 모델 난례만",
            "호출 수·p95·비용 절감과 안전 하한 유지",
            Map.of(CellModelRole.GENERATION, ModelTier.LIGHTWEIGHT,
                    CellModelRole.INPUT_SAFETY, ModelTier.OPERATIONAL,
                    CellModelRole.OUTPUT_JUDGE, ModelTier.OPERATIONAL,
                    CellModelRole.CBT_CLASSIFIER, ModelTier.OPERATIONAL,
                    CellModelRole.ESCALATION, ModelTier.FRONTIER),
            HarnessShape.RULE_FIRST_CASCADE),

    /** E 상위+경량 — 상위 생성 + 중복 Judge 제거·템플릿·조건부 escalation. */
    E("E 상위+경량", "상위 모델", "중복 Judge 제거·템플릿·조건부 escalation",
            "높은 생성 품질로 하네스 비용을 상쇄할 수 있는지",
            Map.of(CellModelRole.GENERATION, ModelTier.FRONTIER,
                    CellModelRole.INPUT_SAFETY, ModelTier.OPERATIONAL,
                    CellModelRole.OUTPUT_JUDGE, ModelTier.OPERATIONAL,
                    CellModelRole.CBT_CLASSIFIER, ModelTier.OPERATIONAL),
            HarnessShape.REDUCED_HARNESS);

    /**
     * 모델 등급.
     *
     * <p>{@link #FRONTIER} 만 기본값이 없다 — 상위 모델 후보는 실행 직전에 사람이 핀해야
     * 하고, 핀하지 않았으면 실행 자체가 막힌다({@link CellModelRegistry}). 기본값을 하나
     * 넣어두면 "그때 어느 모델로 쟀는지" 를 아무도 확인하지 않은 채 수치만 남는다.
     */
    enum ModelTier {
        /** 지금 프로덕션이 쓰는 모델. 기본값이 있고, 그 값은 프로덕션 상수와 같아야 한다. */
        OPERATIONAL,
        /** 상위 모델 후보. 기본값 없음 — 반드시 핀한다. */
        FRONTIER,
        /** 경량 후보. 기본값은 운영 판정 모델과 같지만 별도로 핀할 수 있다. */
        LIGHTWEIGHT
    }

    /**
     * 하네스의 모양 — 로드맵 §11.3 의 "안전·품질 하네스" 열.
     *
     * <p>제거 실험의 단위다. 로드맵은 "Judge trigger 정밀도, 중복 분류기 통합, 프롬프트 압축,
     * response-act 템플릿화, 결정론적 contract 검사 ... 를 하나씩 제거 실험한다" 고 적었다.
     * 여기서는 그중 실행 가능한 세 축(Judge trigger, 중복 Judge, 템플릿화)만 켜고 끈다.
     */
    enum HarnessShape {
        /** 현재 운영 하네스 그대로. */
        CURRENT(true, true, false, false),
        /**
         * 룰·계약 우선. 룰이 판정할 수 있는 턴은 Judge 를 부르지 않고, 고정 응답 행위는
         * 템플릿으로 끝내며, 계약 위반·저신뢰만 상위 모델로 올린다.
         */
        RULE_FIRST_CASCADE(true, true, true, true),
        /**
         * 중복 Judge 제거. pre-filter·계약 검사로 이미 걸러진 턴에 OutputJudge 를 다시 부르지
         * 않는다. 생성 품질이 높다는 전제가 맞는지를 이 스위치가 시험한다.
         */
        REDUCED_HARNESS(true, false, false, true);

        private final boolean inputJudgeEnabled;
        private final boolean outputJudgeEnabled;
        private final boolean conditionalInputJudge;
        private final boolean escalationEnabled;

        HarnessShape(boolean inputJudgeEnabled, boolean outputJudgeEnabled,
                     boolean conditionalInputJudge, boolean escalationEnabled) {
            this.inputJudgeEnabled = inputJudgeEnabled;
            this.outputJudgeEnabled = outputJudgeEnabled;
            this.conditionalInputJudge = conditionalInputJudge;
            this.escalationEnabled = escalationEnabled;
        }

        boolean inputJudgeEnabled() {
            return inputJudgeEnabled;
        }

        boolean outputJudgeEnabled() {
            return outputJudgeEnabled;
        }

        /** 룰이 확정한 턴에서 Judge 호출을 생략하는가. */
        boolean conditionalInputJudge() {
            return conditionalInputJudge;
        }

        boolean escalationEnabled() {
            return escalationEnabled;
        }
    }

    private final String label;
    private final String generationColumn;
    private final String harnessColumn;
    private final String hypothesis;
    private final Map<CellModelRole, ModelTier> roles;
    private final HarnessShape harness;

    BenchmarkCell(String label, String generationColumn, String harnessColumn, String hypothesis,
                  Map<CellModelRole, ModelTier> roles, HarnessShape harness) {
        this.label = label;
        this.generationColumn = generationColumn;
        this.harnessColumn = harnessColumn;
        this.hypothesis = hypothesis;
        this.roles = Map.copyOf(roles);
        this.harness = harness;
    }

    String label() {
        return label;
    }

    String generationColumn() {
        return generationColumn;
    }

    String harnessColumn() {
        return harnessColumn;
    }

    String hypothesis() {
        return hypothesis;
    }

    HarnessShape harness() {
        return harness;
    }

    Map<CellModelRole, ModelTier> roles() {
        return roles;
    }

    /** 이 셀이 요구하는 역할. 선언 순서가 아니라 enum 순서로 고정해 리포트 행이 흔들리지 않게 한다. */
    Set<CellModelRole> requiredRoles() {
        Set<CellModelRole> ordered = new LinkedHashSet<>();
        for (CellModelRole role : CellModelRole.values()) {
            if (roles.containsKey(role)) {
                ordered.add(role);
            }
        }
        return ordered;
    }

    /** 온라인 경로에서 실제 호출되는 역할 — 턴당 원가에 들어가는 것들. */
    Set<CellModelRole> onlineRoles() {
        Set<CellModelRole> ordered = new LinkedHashSet<>();
        requiredRoles().stream().filter(CellModelRole::isOnline).forEach(ordered::add);
        return ordered;
    }

    /** 상위 모델 후보를 요구하는 역할. 하나라도 핀되지 않으면 실행이 막힌다. */
    Set<CellModelRole> frontierRoles() {
        Set<CellModelRole> ordered = new LinkedHashSet<>();
        requiredRoles().stream()
                .filter(role -> roles.get(role) == ModelTier.FRONTIER)
                .forEach(ordered::add);
        return ordered;
    }

    /** 실행 manifest 의 {@code cell} 값. 셀 표의 행을 그대로 읽을 수 있게 가설까지 싣는다. */
    String manifestValue() {
        return "%s (생성=%s / 하네스=%s)".formatted(label, generationColumn, harnessColumn);
    }

    /**
     * {@code -Pcells=A,D} 파싱.
     *
     * <p>모르는 이름을 조용히 건너뛰지 않는다 — 오타 하나로 실행되지 않은 셀이 "실행했는데
     * 결과가 없다" 로 보이면 비교표가 거짓말을 한다.
     */
    static List<BenchmarkCell> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(values());
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(name -> {
                    try {
                        return valueOf(name.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "알 수 없는 셀 이름: '%s'. 가능한 값: %s"
                                        .formatted(name, Arrays.toString(values())), e);
                    }
                })
                .distinct()
                .toList();
    }
}
