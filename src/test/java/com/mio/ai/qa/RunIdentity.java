package com.mio.ai.qa;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "이 두 수치가 같은 실행·같은 버전에서 나왔는가" 를 <b>값으로</b> 대답하는 도장.
 *
 * <h2>왜 값이어야 하는가</h2>
 *
 * <p>이전에는 교차 실행 비교를 막는 것이 {@code appendVerdicts()} 가 같은 JVM 안의 맵만
 * 들여다본다는 <b>사실</b>이었다. 그건 가드가 아니라 정황이다 — {@link CellGoNoGo#evaluate}
 * 자체는 두 인자가 같은 실행인지, 같은 잠금 세트 버전인지, 같은 정책·프롬프트 버전인지,
 * 같은 단가 기준일인지 아무것도 묻지 않았다. 아카이브를 읽어 비교하는 도구가 나중에 생기면
 * 그 구멍이 조용히 다시 열린다. 나머지 정직성 가드가 전부 타입으로 강제되는데 이것만
 * 관행이었다.
 *
 * <p>그래서 실행 1회당 한 번 도장을 만들어 모든 {@link CellRunner.Result} 에 박고,
 * {@code evaluate()} 가 <b>도장을 비교해서</b> 다르면 {@link CellGoNoGo.Verdict#NOT_EVALUABLE}
 * 로 닫는다. 도장은 manifest 에도 실리므로, 아카이브만 읽는 미래의 도구도 같은 비교를 할 수
 * 있다 — 기록에 없으면 비교하지 않는 편이 맞다.
 *
 * <h2>무엇을 도장에 넣는가</h2>
 *
 * <p>다르면 수치의 의미가 달라지는 것만 넣는다.
 *
 * <ul>
 *   <li>{@code runId} — 같은 {@code runCells()} 호출인가. 이것 하나로 교차 실행이 걸린다.</li>
 *   <li>{@code datasetVersion} — 같은 잠금 세트인가 ({@link LockedEvalSet#VERSION}).</li>
 *   <li>{@code lockedSetSha256} — 버전 문자열은 그대로인데 내용이 바뀐 경우까지 잡는다.</li>
 *   <li>{@code policyVersion} / {@code promptVersion} — 같은 판단 규칙으로 돌았는가.</li>
 *   <li>{@code pricingAsOf} — 원가 비교가 같은 시점 단가로 계산됐는가.</li>
 * </ul>
 *
 * <p>모델 ID 는 <b>넣지 않는다.</b> 셀마다 다른 것이 정상이고, 다른 것을 비교하는 것이 이
 * 하네스의 목적이기 때문이다. 후보 스크리닝에서 셀 B/후보1 과 B/후보2 가 같은 도장을 갖는
 * 것도 같은 이유다 — 둘은 같은 실행의 서로 다른 팔이다.
 */
record RunIdentity(UUID runId, String datasetVersion, String lockedSetSha256,
                   String policyVersion, String promptVersion, String pricingAsOf) {

    RunIdentity {
        require("run_id", runId == null ? null : runId.toString());
        require("dataset_version", datasetVersion);
        require("locked_set_sha256", lockedSetSha256);
        require("policy_version", policyVersion);
        require("prompt_version", promptVersion);
        require("pricing_as_of", pricingAsOf);
    }

    /**
     * 실행 1회의 도장을 찍는다. {@code runCells()} 안에서 <b>한 번만</b> 부른다.
     *
     * @param pricingAsOf 단가 기준일. 실행 전체가 하나의 단가표를 쓰므로 실행 단위 값이다
     */
    static RunIdentity stamp(String pricingAsOf) {
        return new RunIdentity(UUID.randomUUID(), LockedEvalSet.VERSION, LockedEvalSet.fileSha256(),
                CellReport.policyVersion(), EvalRunManifest.UNVERSIONED, pricingAsOf);
    }

    /**
     * 두 도장이 같은 실행·같은 버전인가.
     *
     * @return 같으면 {@code null}, 다르면 어느 항목이 어떻게 다른지 적은 사유
     */
    String mismatchAgainst(RunIdentity other) {
        if (other == null) {
            return "비교 대상의 실행 도장이 없다";
        }
        if (!runId.equals(other.runId)) {
            return "서로 다른 실행이다 (%s vs %s) — 코드·프롬프트·정책·네트워크 조건이 같다는 보장이 없다"
                    .formatted(runId, other.runId);
        }
        String field = firstDifferingField(other);
        return field == null ? null
                : "같은 실행인데 %s 가 다르다 — 실행 중 값이 바뀌었다는 뜻이라 더 위험하다".formatted(field);
    }

    private String firstDifferingField(RunIdentity other) {
        if (!datasetVersion.equals(other.datasetVersion)) {
            return "잠금 세트 버전";
        }
        if (!lockedSetSha256.equals(other.lockedSetSha256)) {
            return "잠금 세트 내용 해시";
        }
        if (!policyVersion.equals(other.policyVersion)) {
            return "정책 버전";
        }
        if (!promptVersion.equals(other.promptVersion)) {
            return "프롬프트 버전";
        }
        if (!pricingAsOf.equals(other.pricingAsOf)) {
            return "단가 기준일";
        }
        return null;
    }

    /**
     * manifest 에 실을 항목.
     *
     * <p>정책·프롬프트·단가 기준일·데이터셋 버전은 manifest 가 이미 자기 자리에 기록하므로
     * 여기서는 실행 식별자와 내용 해시만 낸다. 같은 값을 두 이름으로 적으면 둘이 갈렸을 때
     * 어느 쪽이 맞는지 다시 판단해야 한다.
     */
    Map<String, String> asManifestFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("run_id", runId.toString());
        fields.put("run_identity_note",
                "이 실행의 수치는 같은 run_id·같은 버전 도장을 가진 결과끼리만 비교할 수 있다");
        return fields;
    }

    private static void require(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " 가 비었다 — 도장이 비면 같은 실행인지 확인할 수 없고, "
                            + "확인할 수 없는 비교는 하지 않는다");
        }
    }
}
