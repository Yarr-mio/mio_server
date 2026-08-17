package com.mio.ai.qa;

import com.mio.ai.qa.LockedEvalSet.LockedCase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 계약 준수율 실측과 계약 지시 A/B (이슈 #305, 로드맵 §5.8). <b>실 LLM 을 부른다.</b>
 *
 * <h2>무엇을 재는가</h2>
 *
 * <p>{@code #303} 이 만든 계약을 실제 모델이 지키는지, 그리고 {@code [응답 계약]} 프롬프트
 * 블록이 위반율을 실제로 낮추는지. 같은 케이스 목록·같은 {@link RunIdentity} 아래에서 두 팔을
 * 나란히 돌린다 — 실행을 나눠 돌린 결과는 비교할 수 없다.
 *
 * <h2>왜 잠금 gold 가 아닌가</h2>
 *
 * <p>두 가지 이유가 각각 단독으로 결정적이며 {@link ContractEvalSet} 에 적혀 있다. 요약하면
 * (1) 잠금 세트는 "프롬프트 튜닝" 을 금지 용도로 명시했고 이 A/B 의 결과는 프롬프트 결정의
 * 근거이며, (2) 잠금 세트의 계약 적용 모집단은 301건 중 12건이라 {@code minSubgroupN=30} 에
 * 미달해 비율 자체를 낼 수 없다.
 *
 * <h2>실행</h2>
 *
 * <pre>{@code
 * # 견적 먼저 (무과금)
 * ./gradlew test --tests "com.mio.ai.qa.ContractComplianceCostEstimateTest"
 *
 * # 전량 (120건 × 2팔 = 240턴)
 * ./gradlew test -PllmTests -PpricingAsOf=<YYYY-MM-DD> \
 *   --tests "com.mio.ai.qa.ContractComplianceLlmTest"
 *
 * # 기준선으로 저장소에 남길 때
 * ./gradlew test -PllmTests -PevalArchiveDir=docs/eval/runs ...
 * }</pre>
 */
@Tag("llm-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 응답 계약 준수율 실측 · 계약 지시 A/B (실 LLM)")
class ContractComplianceLlmTest {

    private static final String SAMPLE_PROPERTY = "mio.eval.sampleSize";

    /**
     * 외부 실패 상한 (비율).
     *
     * <p>실패한 턴은 계약 모집단에 들어오지 않는다. 실패가 많으면 남은 모집단이 실패하지 않은
     * 쪽으로 치우쳐, 위반율이 네트워크 사정을 재는 값이 된다.
     */
    private static final double MAX_EXTERNAL_FAILURE_SHARE = 0.10;

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    @DisplayName("계약 지시 유무로 두 팔을 돌리고 행위별 위반율·분포를 기록한다")
    void measureContractCompliance() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && apiKey.startsWith("sk-"),
                "OPENAI_API_KEY 가 없다 — 실 LLM 계약 준수 실측을 건너뛴다");

        List<LockedCase> cases = cases();
        boolean sampled = cases.size() < ContractEvalSet.CASES.size();
        RunIdentity identity = RunIdentity.stamp(
                System.getProperty(CellModelRegistry.PRICING_AS_OF_PROPERTY,
                        EvalRunManifest.PRICING_DATE_UNRECORDED));

        System.out.printf("%n[contract-compliance] 세트 %s · %d건 × 2팔%s · run_id %s%n",
                ContractEvalSet.VERSION, cases.size(), sampled ? " (표본)" : " (전량)",
                identity.runId());

        Map<ContractPromptArm, CellRunner.Result> runs = new LinkedHashMap<>();
        Map<ContractPromptArm, ContractComplianceMetrics> metrics = new LinkedHashMap<>();
        for (ContractPromptArm arm : ContractPromptArm.values()) {
            CellVariant variant = CellVariant.of(BenchmarkCell.A, arm);
            CellRunner.Result result = CellRunner
                    .realLlm(variant, CellModelRegistry.resolveForVariant(variant), apiKey)
                    .run(cases, sampled, identity);
            ContractComplianceMetrics armMetrics = ContractComplianceMetrics.of(result);
            String report = ContractComplianceReport.render(result, armMetrics);
            System.out.print(report);
            ContractComplianceReport.archive(result, armMetrics, report);
            runs.put(arm, result);
            metrics.put(arm, armMetrics);
        }

        ContractComplianceMetrics with = metrics.get(ContractPromptArm.WITH_CONTRACT_BLOCK);
        ContractComplianceMetrics without = metrics.get(ContractPromptArm.WITHOUT_CONTRACT_BLOCK);
        String comparison = ContractComplianceReport.renderComparison(with, without);
        System.out.print(comparison);
        ContractComplianceReport.archiveComparison(
                runs.get(ContractPromptArm.WITH_CONTRACT_BLOCK), with, without, comparison);

        // ── 이 실행이 인용 가능한 값을 냈는지만 검사한다 ──────────────
        //
        // 위반율의 방향은 검사하지 않는다. 결과를 미리 정해 두고 그 방향으로 통과시키는 평가는
        // 평가가 아니다. 검사하는 것은 "이 실행이 답을 낼 수 있는 상태였는가" 뿐이다.
        metrics.forEach((arm, m) -> {
            assertThat(m.applicable())
                    .as("%s 팔에서 계약 적용 턴이 하한 미만이다 (%d) — P0-8 3단계와 같이 "
                            + "건수만 인용할 수 있는 실행이 됐다. 세트가 아니라 라우팅이 바뀐 것인지 "
                            + "먼저 확인한다 (위기 라우팅 %d · 계획 밖 %d)",
                            arm.label(), m.applicable(), m.crisisRouted(), m.unplanned())
                    .isGreaterThanOrEqualTo(LockedEvalSet.REPORTING.minSubgroupN());
            assertThat(m.violationRate())
                    .as("%s 팔의 총계 위반율이 보고 가능해야 한다", arm.label())
                    .isInstanceOf(ReportableRate.Reported.class);
            assertThat((double) m.externalFailures() / m.cases())
                    .as("%s 팔의 외부 실패가 많다 (%d/%d) — 남은 모집단이 편향된다",
                            arm.label(), m.externalFailures(), m.cases())
                    .isLessThanOrEqualTo(MAX_EXTERNAL_FAILURE_SHARE);
        });

        assertThat(runs.values())
                .as("두 팔이 같은 실행 도장을 갖지 않으면 비교할 수 없다")
                .allSatisfy(result -> assertThat(result.identity()).isEqualTo(identity));
    }

    /** 파일럿용 축소 실행을 열어 둔다. 축소 실행은 하위 그룹 하한에 걸려 행위별 비율이 사라진다. */
    private List<LockedCase> cases() {
        String size = System.getProperty(SAMPLE_PROPERTY);
        if (size == null || size.isBlank()) {
            return ContractEvalSet.CASES;
        }
        return StratifiedSampler.sample(ContractEvalSet.CASES, LockedCase::subgroup,
                Integer.parseInt(size.trim()), CellModelRegistry.DEFAULT_SEED);
    }
}
