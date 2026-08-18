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
 * <h2>두 팔이 각자 InputJudge 를 부르는 이유</h2>
 *
 * <p>한 팔의 판정과 계획을 계산해 두 팔이 재사용하면 <b>완전 페어링</b>이 되고 케이스당 판정
 * 호출도 하나 줄어든다. 채점은 어차피 항상 실제 계획을 쓰므로 구조적으로도 어렵지 않다.
 * 그런데도 각자 부르는 쪽을 택했다 — 프로덕션은 매 턴 판정을 부르고, 판정을 한 번만 불러
 * 돌려쓰는 실행은 <b>프로덕션 경로를 재구성한 것이 아니다.</b> 이 평가의 전제는
 * "셀 벤치마크와 같은 경로를 같은 자로 잰다" 이고, 그 전제를 깨서 얻는 페어링은 비교 가능성을
 * 대가로 치른다. 그 대신 페어링이 완전하지 않다는 사실을 리포트가 명시하고, 행위별 n 을
 * 팔마다 따로 싣는다.
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
                            + "건수만 인용할 수 있는 실행이 됐다. 이탈은 셋이다: "
                            + "① Judge 위기 승격 %d건 · ② Judge 보안 의심 %d건 · "
                            + "③ 생성 본문 없음 %d건. ①②만 무과금 게이트(ContractEvalSetTest)가 "
                            + "닫으며, 그 게이트는 플래너 층까지만 보므로 ③은 여기서만 보인다",
                            arm.label(), m.applicable(), m.crisisRouted(), m.unplanned(),
                            m.noBodyEscapes())
                    .isGreaterThanOrEqualTo(LockedEvalSet.REPORTING.minSubgroupN());
            assertThat(m.violationRate())
                    .as("%s 팔의 총계 위반율이 보고 가능해야 한다", arm.label())
                    .isInstanceOf(ReportableRate.Reported.class);
            // 외부 실패는 acceptance 라벨이 아니라 관측된 사실로 센다 (P0-3). #305 실행은
            // 생성 실패 25건 중 24건이 같은 턴의 판정 실패 라벨에 먹혀 이 검사가 16.3% 를
            // 0.65% 로 읽고 통과했다.
            assertThat(m.externalFailureWithinLimit())
                    .as("%s 팔의 외부 실패가 상한을 넘었다 — %d/%d턴 (%.1f%%) > %.0f%%. "
                            + "내역: 생성 %d · 판정 %d · 케이스 중단 %d. 남은 %d건은 무작위 표본이 "
                            + "아니므로 위반율을 인용할 수 없다",
                            arm.label(), m.externalFailures(), m.cases(),
                            m.externalFailureShare() * 100,
                            ContractComplianceMetrics.MAX_EXTERNAL_FAILURE_SHARE * 100,
                            m.generationFailures(), m.judgeFailures(), m.abortedCases(),
                            m.applicable())
                    .isTrue();
            assertThat(m.unexplainedEscapes())
                    .as("%s 팔의 계약 밖 %d건 중 %d건이 어느 이탈로도 설명되지 않는다 — "
                            + "'계약 밖 N건' 이 다시 설명 없이 남았다",
                            arm.label(), m.notApplicable(), m.unexplainedEscapes())
                    .isZero();
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
