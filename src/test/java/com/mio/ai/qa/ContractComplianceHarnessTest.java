package com.mio.ai.qa;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.plan.GenerationFreedom;
import com.mio.ai.plan.ResponseAct;
import com.mio.ai.plan.ResponseContractValidator;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.prompt.PromptBuilder;
import com.mio.ai.qa.CellCaseOutcome.ContractOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

/**
 * 계약 준수 하네스 자체 검사 (이슈 #305). 모델을 부르지 않는다.
 *
 * <p>유료 실행 전에 답해야 하는 것은 셋이다. (1) A/B 가 프롬프트만 가르는가, (2) 위반이
 * 실제로 잡히고 유형별로 세어지는가, (3) 하한 미달 행위의 비율이 계산되지 않는가.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("[QA] 계약 준수 하네스 자체 검사 (모델 호출 없음)")
class ContractComplianceHarnessTest {

    private static final RunIdentity IDENTITY = RunIdentity.stamp("2026-08-17");

    /** 계약이 걸리는 계획 하나. 프롬프트 조립만 볼 때 쓴다. */
    private static final ResponsePlan PLAN = new ResponsePlan(
            ResponseAct.EMPATHIC_REFLECTION, GenerationFreedom.CONSTRAINED, 1, 3,
            List.of("diagnosis", "certainty_about_user", "guaranteed_outcome",
                    "cbt_intervention", "advice"));

    // ── A/B 가 정확히 무엇을 가르는가 ────────────────────────────────

    @Test
    @DisplayName("대조군 프롬프트에서 [응답 계약] 블록만 빠진다")
    void controlArmDropsOnlyTheContractBlock() {
        PromptBuilder builder = new PromptBuilder();
        String with = builder.buildSystemPrompt(GenerationMode.GUARDED, InterventionHints.empty(),
                null, com.mio.character.domain.CharacterPersona.DEFAULT.characterId(), null,
                ContractPromptArm.WITH_CONTRACT_BLOCK.promptPlan(PLAN));
        String without = builder.buildSystemPrompt(GenerationMode.GUARDED, InterventionHints.empty(),
                null, com.mio.character.domain.CharacterPersona.DEFAULT.characterId(), null,
                ContractPromptArm.WITHOUT_CONTRACT_BLOCK.promptPlan(PLAN));

        assertThat(with).contains("[응답 계약]");
        assertThat(without).doesNotContain("[응답 계약]");
        assertThat(without)
                .as("계약 블록 말고 다른 것이 함께 빠지면 A/B 의 차이가 계약의 효과가 아니게 된다")
                .isEqualTo(with.replace(with.substring(with.indexOf("\n\n[응답 계약]"),
                        with.length()), ""));
    }

    @Test
    @DisplayName("대조군도 진짜 계획으로 채점된다 — 채점 기준은 팔에 따라 바뀌지 않는다")
    void bothArmsAreScoredWithTheRealPlan() {
        assertThat(ContractPromptArm.WITH_CONTRACT_BLOCK.promptPlan(PLAN)).isSameAs(PLAN);
        assertThat(ContractPromptArm.WITHOUT_CONTRACT_BLOCK.promptPlan(PLAN)).isNull();

        // 검사에 쓰이는 계획은 프롬프트 팔과 무관하게 하나뿐이다.
        ResponseContractValidator validator = new ResponseContractValidator();
        String violating = "지금 많이 힘드시겠어요. 그건 분명히 지나갈 거예요. "
                + "잠깐 산책이라도 해보세요. 언제부터 그랬나요? 오늘은 어떠셨어요?";
        assertThat(validator.validate(PLAN, violating).passed()).isFalse();
    }

    @Test
    @DisplayName("계약 팔이 기본값이면 변형 이름·파일명이 예전과 같다")
    void defaultArmKeepsExistingLabels() {
        assertThat(CellVariant.of(BenchmarkCell.A).label()).isEqualTo("A");
        assertThat(new CellVariant(BenchmarkCell.B, "gpt-4.1-mini").fileLabel())
                .isEqualTo("b-gpt-4.1-mini");
        assertThat(CellVariant.of(BenchmarkCell.A, ContractPromptArm.WITHOUT_CONTRACT_BLOCK).label())
                .isEqualTo("A/without-contract");
        assertThat(CellVariant.of(BenchmarkCell.A, ContractPromptArm.WITHOUT_CONTRACT_BLOCK)
                .fileLabel())
                .as("두 팔이 같은 파일명을 쓰면 아카이브가 서로를 덮는다")
                .isEqualTo("a-without-contract");
    }

    @Test
    @DisplayName("실행이 실제로 팔마다 다른 프롬프트를 보낸다")
    void runnerSendsDifferentPromptsPerArm() {
        PromptSpy withSpy = new PromptSpy();
        PromptSpy withoutSpy = new PromptSpy();
        run(ContractPromptArm.WITH_CONTRACT_BLOCK, withSpy);
        run(ContractPromptArm.WITHOUT_CONTRACT_BLOCK, withoutSpy);

        assertThat(withSpy.generationPrompts())
                .as("계약 팔인데 계약 블록이 없는 프롬프트가 있다")
                .isNotEmpty()
                .allSatisfy(prompt -> assertThat(prompt).contains("[응답 계약]"));
        assertThat(withoutSpy.generationPrompts())
                .isNotEmpty()
                .allSatisfy(prompt -> assertThat(prompt).doesNotContain("[응답 계약]"));
    }

    // ── 지표 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("스텁 실행에서 계약 모집단과 행위 분포가 나온다")
    void stubRunProducesAContractPopulation() {
        ContractComplianceMetrics metrics = metricsOf(run(ContractPromptArm.WITH_CONTRACT_BLOCK,
                new PromptSpy()));

        System.out.print(ContractComplianceReport.render(
                run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy()), metrics));

        assertThat(metrics.applicable())
                .as("계약 적용 턴이 없으면 이 평가는 아무것도 재지 않는다")
                .isEqualTo(ContractEvalSet.CASES.size());
        assertThat(metrics.byAct().get(ResponseAct.CLARIFY_CONTEXT).applicable())
                .as("스텁 InputJudge 는 CLEAR_LOW 를 돌려주므로 룰 승격이 남아 CLARIFY_CONTEXT 가 된다")
                .isEqualTo(ContractEvalSet.CASES.size());
        assertThat(metrics.crisisRouted()).isZero();
        assertThat(metrics.unplanned()).isZero();
    }

    @Test
    @DisplayName("위반이 유형별로 세어진다 — 상한 위반의 실제 수치는 유형으로 접힌다")
    void violationsAreCountedByType() {
        // 계약을 대놓고 깨는 본문: 문장 5개·질문 2개·조언·단정.
        String violating = "많이 힘드셨겠어요. 그건 분명히 지나갈 거예요. "
                + "잠깐이라도 산책을 해보세요. 언제부터 그랬나요? 오늘은 어떠셨어요?";
        ContractComplianceMetrics metrics = metricsOf(
                run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy(), violating));

        assertThat(metrics.violated()).isEqualTo(metrics.applicable());
        assertThat(metrics.violationTypes().keySet())
                .as("유형 분포가 비면 '무엇이 가장 자주 깨지는가' 에 답할 수 없다")
                .contains("max_sentences", "max_questions");
        assertThat(metrics.violationTypes().keySet())
                .as("max_questions(3>1) 처럼 수치가 붙은 채로 세면 유형 분포가 아니라 값 분포가 된다")
                .allSatisfy(type -> assertThat(type).doesNotContain("("));
        assertThat(metrics.shape().maxSentences()).isGreaterThan(3);
    }

    @Test
    @DisplayName("하한 미달 행위는 비율이 계산되지 않는다")
    void ratesBelowTheFloorAreSuppressed() {
        ContractComplianceMetrics metrics = metricsOf(run(ContractPromptArm.WITH_CONTRACT_BLOCK,
                new PromptSpy()));

        assertThat(metrics.byAct().get(ResponseAct.EMPATHIC_REFLECTION).violationRate())
                .as("모집단이 0 인 행위에 비율이 붙으면 안 된다")
                .isInstanceOf(ReportableRate.Suppressed.class);
        assertThat(metrics.byAct().get(ResponseAct.CLARIFY_CONTEXT).violationRate())
                .as("하한을 넘긴 행위는 비율을 낸다 — 하한이 모든 것을 막는 규칙이면 쓸모가 없다")
                .isInstanceOf(ReportableRate.Reported.class);
        assertThat(ContractComplianceReport.render(
                run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy()), metrics))
                .contains("미보고 (n=0");
    }

    @Test
    @DisplayName("A/B 리포트는 한쪽이라도 하한 미달이면 비율 차이를 내지 않는다")
    void comparisonRefusesDeltaWhenEitherArmIsSuppressed() {
        ContractComplianceMetrics with = metricsOf(run(ContractPromptArm.WITH_CONTRACT_BLOCK,
                new PromptSpy()));
        ContractComplianceMetrics without = metricsOf(run(ContractPromptArm.WITHOUT_CONTRACT_BLOCK,
                new PromptSpy()));

        String comparison = ContractComplianceReport.renderComparison(with, without);
        System.out.print(comparison);

        assertThat(comparison).contains("비율 비교 불가 (한쪽 이상 하한 미달)");
        assertThat(comparison)
                .as("이 A/B 가 답하지 못하는 것을 리포트가 스스로 적어야 한다")
                .contains("답 못 한다");
    }

    @Test
    @DisplayName("스텁 실행은 아카이브를 남기지 않는다")
    void stubRunsAreNotArchived() {
        CellRunner.Result result = run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy());
        ContractComplianceMetrics metrics = metricsOf(result);

        assertThat(catchThrowable(() ->
                ContractComplianceReport.archive(result, metrics, "본문")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("스텁 실행");
    }

    @Test
    @DisplayName("manifest 가 dev_gold 와 튜닝 노출을 그대로 싣는다")
    void manifestCarriesTheHonestSplit() {
        CellRunner.Result result = run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy());
        EvalRunManifest manifest = ContractComplianceReport.manifest(
                result, metricsOf(result), Map.of("stub", "true"));

        Map<String, String> metadata = manifest.toMetadata();
        assertThat(metadata.get("dataset_split")).isEqualTo("dev_gold");
        assertThat(metadata.get("tuning_exposure"))
                .isEqualTo(EvalRunManifest.TuningExposure.USED_FOR_TUNING.attestation());
        assertThat(metadata.get("dataset")).isEqualTo(ContractEvalSet.VERSION);
        assertThat(metadata.get("scope")).contains("contract compliance (dev-gold)");
    }

    // ── P0-3: 외부 실패 계량기가 판정 실패에 가려지지 않는다 ──────────

    /**
     * {@code #305} 유료 실행이 실제로 낸 실패 모양 (아카이브 {@code RUN-NOTES.md} 3번).
     *
     * <p>대조군 153건 중 <b>생성 호출 25건</b>이 실패했고(관측 서명 {@code LLM streaming error}),
     * 그중 <b>24건</b>은 같은 턴의 InputJudge 호출도 실패했다({@code LLM complete error}).
     * 이 모양을 그대로 재구성한다 — 숫자를 맞추는 것이 목적이 아니라, 저 실행에서 계량기가
     * 무엇을 놓쳤는지가 이 모양에서만 드러나기 때문이다.
     */
    private static final int GENERATION_FAILURES = 25;
    private static final int JUDGE_FAILURES_AMONG_THEM = 24;

    /** {@code #305} 대조군 세트 크기. 이 값이 곧 외부 실패 비율의 분모다. */
    private static final int RUN_CASES = 153;

    @Test
    @DisplayName("P0-3: 판정 실패가 생성 실패를 덮어써도 외부 실패 계량기는 25건을 센다 — #305 재구성")
    void externalFailureTallySurvivesTheJudgeFailureLabel() {
        assertThat(ContractEvalSet.CASES)
                .as("#305 실행과 같은 분모여야 16.3% 를 재구성할 수 있다")
                .hasSize(RUN_CASES);

        ContractComplianceMetrics metrics = metricsOf(runWithFailureShape());

        // ── 사실: 두 실패가 서로를 지우지 않는다 ──────────────────────
        assertThat(metrics.generationFailures())
                .as("생성 호출 실패 사실이 판정 실패 라벨에 먹히면 안 된다")
                .isEqualTo(GENERATION_FAILURES);
        assertThat(metrics.judgeFailures())
                .as("판정 호출 실패도 같은 턴에서 따로 세어져야 한다")
                .isEqualTo(JUDGE_FAILURES_AMONG_THEM);
        assertThat(metrics.externalFailures())
                .as("한 턴에서 두 실패가 겹쳐도 그 턴은 한 번 세고, 겹침 때문에 사라지지는 않는다")
                .isEqualTo(GENERATION_FAILURES);

        // ── 비율: 0.65% 가 아니라 16.3% 로 읽힌다 ─────────────────────
        assertThat(metrics.externalFailureShare() * 100)
                .as("#305 manifest 는 external_failure_calls=1 → 0.65%% 로 읽고 통과했다. "
                        + "실제 유실은 %d/%d = 16.3%% 다",
                        GENERATION_FAILURES, RUN_CASES)
                .isCloseTo(16.3, within(0.1));
        assertThat((double) (GENERATION_FAILURES - JUDGE_FAILURES_AMONG_THEM) / RUN_CASES * 100)
                .as("예전 계량기가 읽던 값을 못 박아 둔다 — 25 − 24 = 1 → 0.65%%")
                .isCloseTo(0.65, within(0.01));

        // ── 가드: 같은 데이터에서 이제 거절한다 ────────────────────────
        assertThat(metrics.externalFailureWithinLimit())
                .as("외부 실패 %.1f%% 가 상한 %.0f%% 를 넘었는데 가드가 통과시키면 P0-3 이 그대로 남는다",
                        metrics.externalFailureShare() * 100,
                        ContractComplianceMetrics.MAX_EXTERNAL_FAILURE_SHARE * 100)
                .isFalse();
    }

    @Test
    @DisplayName("P0-3: acceptance 는 여전히 턴당 하나 — 판정 실패 라벨이 우선한다")
    void acceptanceStillCarriesExactlyOneLabelPerTurn() {
        List<CellCaseOutcome> outcomes = runWithFailureShape().outcomes();

        // 라벨 의미론은 바뀌지 않았다. 바뀐 것은 계량기가 라벨을 읽지 않는다는 것뿐이다.
        assertThat(outcomes).filteredOn(o ->
                        o.acceptance() == CellCaseOutcome.Acceptance.REJECTED_JUDGE_FAILURE)
                .as("판정이 실패한 턴은 여전히 REJECTED_JUDGE_FAILURE 로 라벨된다")
                .hasSize(JUDGE_FAILURES_AMONG_THEM);
        assertThat(outcomes).filteredOn(o ->
                        o.acceptance() == CellCaseOutcome.Acceptance.REJECTED_EXTERNAL_FAILURE)
                .as("라벨만 세면 25건 중 1건만 남는다 — 이 값이 #305 manifest 의 1 이다")
                .hasSize(GENERATION_FAILURES - JUDGE_FAILURES_AMONG_THEM);

        // 두 라벨의 턴 모두 외부 실패 사실을 들고 있다.
        assertThat(outcomes).filteredOn(CellCaseOutcome::externalFailureObserved)
                .as("라벨이 무엇이든 외부 실패가 일어난 턴은 사실을 들고 있어야 한다")
                .hasSize(GENERATION_FAILURES)
                .allSatisfy(o -> assertThat(o.externalFailure().generation()).isTrue());
    }

    @Test
    @DisplayName("P0-3: 계약 밖 25건이 이탈③으로 이름을 얻고 합계가 검산된다")
    void theThirdEscapeIsNamedAndAudited() {
        CellRunner.Result result = runWithFailureShape();
        ContractComplianceMetrics metrics = metricsOf(result);

        assertThat(metrics.notApplicable())
                .as("#305 대조군의 '계약 밖 25건' 을 재구성한다")
                .isEqualTo(GENERATION_FAILURES);
        assertThat(metrics.crisisRouted()).as("이탈① 은 0 이었다").isZero();
        assertThat(metrics.unplanned()).as("이탈② 는 0 이었다").isZero();
        assertThat(metrics.securityRefusal()).as("보안 거절도 0 이었다").isZero();
        assertThat(metrics.noBodyEscapes())
                .as("설명 줄이 모두 0 인데 계약 밖 25건이 남는 상태를 이탈③ 이 메운다")
                .isEqualTo(GENERATION_FAILURES);
        assertThat(metrics.unexplainedEscapes())
                .as("이탈 합계가 계약 밖 건수와 맞지 않으면 또 이름 없는 이탈이 있다는 뜻이다")
                .isZero();

        String report = ContractComplianceReport.render(result, metrics);
        System.out.print(report);
        assertThat(report)
                .as("리포트가 세 번째 이탈을 이름으로 찍어야 '계약 밖 N건' 이 다시 미설명으로 남지 않는다")
                .contains("이탈③ 생성 본문 없음")
                .contains("이탈 합계 ①+②+③+보안거절");
        assertThat(report)
                .as("무과금 게이트의 보장 범위가 플래너 층이라는 것을 리포트가 스스로 적어야 한다")
                .contains("플래너 층에 한정");
        assertThat(report)
                .as("상한을 넘긴 실행은 리포트가 인용 금지를 찍어야 한다")
                .contains("외부 실패가 상한을 넘었다");
    }

    @Test
    @DisplayName("P0-3: manifest 가 외부 실패를 턴 단위·내역과 함께 싣는다")
    void manifestCarriesTheHonestExternalFailureTally() {
        CellRunner.Result result = runWithFailureShape();
        Map<String, String> metadata = ContractComplianceReport
                .manifest(result, metricsOf(result), Map.of()).toMetadata();

        assertThat(metadata.get("external_failure_turns"))
                .as("#305 manifest 는 이 자리에 1 을 적었다")
                .isEqualTo(String.valueOf(GENERATION_FAILURES));
        assertThat(metadata.get("external_failure_share")).startsWith("0.1634");
        assertThat(metadata.get("external_failure_within_limit")).isEqualTo("false");
        assertThat(metadata.get("external_failure_breakdown"))
                .isEqualTo("생성 %d · 판정 %d · 케이스 중단 0"
                        .formatted(GENERATION_FAILURES, JUDGE_FAILURES_AMONG_THEM));
        assertThat(metadata.get("no_body_escapes")).isEqualTo(String.valueOf(GENERATION_FAILURES));
        assertThat(metadata)
                .as("단위가 '호출' 이 아니었던 옛 키를 남겨 두면 같은 이름이 두 정의를 갖는다")
                .doesNotContainKey("external_failure_calls");
    }

    // ── P0-3: reporting_unit 은 보고 대상 세트를 말한다 ────────────────

    @Test
    @DisplayName("P0-3: reporting_unit 이 잠금 세트의 고정 문구를 쓰지 않는다")
    void reportingUnitDescribesTheContractSet() {
        CellRunner.Result result = run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy());
        ContractComplianceMetrics metrics = metricsOf(result);
        Map<String, String> metadata = ContractComplianceReport
                .manifest(result, metrics, Map.of()).toMetadata();

        String unit = metadata.get("reporting_unit");
        assertThat(unit)
                .as("이 세트의 하위 그룹은 51건씩이라 하한 30 을 넘는다 — 잠금 세트 문구는 거짓이 된다")
                .doesNotContain(LockedEvalSet.REPORTING.reportableUnit())
                .doesNotContain("어느 하위 그룹도");
        assertThat(unit)
                .as("보고 대상 세트와 이번 실행의 관측값을 말해야 한다")
                .contains(ContractEvalSet.VERSION)
                .contains("CLARIFY_CONTEXT=" + ContractEvalSet.CASES.size());
        assertThat(metadata.get("reporting_min_subgroup_n"))
                .as("하한 자체는 두 세트가 공유한다")
                .isEqualTo(String.valueOf(LockedEvalSet.REPORTING.minSubgroupN()));
        assertThat(ContractEvalSet.intendedDistribution().values())
                .as("선언 하위 그룹이 모두 하한을 넘는다는 전제가 이 문구의 근거다")
                .allSatisfy(n -> assertThat(n)
                        .isGreaterThanOrEqualTo(LockedEvalSet.REPORTING.minSubgroupN()));
    }

    // ── P0-3: 원가가 리포트·manifest 에 실린다 ─────────────────────────

    @Test
    @DisplayName("P0-3: 리포트와 manifest 가 토큰·원가를 싣는다 — 견적 대비 실비를 답할 수 있다")
    void costIsRenderedSoEstimateVersusActualIsAnswerable() {
        CellRunner.Result result = run(ContractPromptArm.WITH_CONTRACT_BLOCK, new PromptSpy());
        ContractComplianceMetrics metrics = metricsOf(result);

        assertThat(ContractComplianceReport.render(result, metrics))
                .as("#305 는 이 줄이 없어 실비를 어디에서도 확인할 수 없었다")
                .contains("[토큰·원가")
                .contains("총 원가")
                .contains("단가 미등록 호출");

        Map<String, String> metadata = ContractComplianceReport
                .manifest(result, metrics, Map.of()).toMetadata();
        assertThat(metadata)
                .as("CellReport 와 같은 키를 써야 두 아카이브를 같은 도구로 읽을 수 있다")
                .containsKeys("llm_calls", "prompt_tokens", "completion_tokens",
                        "cost_total_usd", "unpriced_calls", "usage_missing_calls");
        assertThat(Long.parseLong(metadata.get("prompt_tokens"))).isPositive();
        assertThat(metadata.get("cost_total_usd"))
                .as("단가를 아는 실행이면 금액이, 모르면 '미상' 이 나온다 — 0 은 나오지 않는다")
                .isNotEqualTo("$0.000000");
    }

    @Test
    @DisplayName("P0-3: 세트가 선언한 이탈과 리포트가 이름 붙이는 이탈이 같은 수다")
    void declaredEscapesMatchWhatTheReportNames() {
        // 세트의 산문과 실행의 집계가 갈리는 것이 P0-3 의 본질이었다. 한쪽만 고치면 여기서 깨진다.
        assertThat(ContractEvalSet.declaredEscapes())
                .as("리포트는 이탈① ② ③ 을 이름으로 찍는다 — 세트 선언도 셋이어야 한다")
                .hasSize(3)
                .anySatisfy(e -> assertThat(e).startsWith("JUDGE_HARD_CRISIS"))
                .anySatisfy(e -> assertThat(e).startsWith("JUDGE_SECURITY"))
                .anySatisfy(e -> assertThat(e).startsWith("NO_BODY"));
        assertThat(ContractEvalSet.declaredEscapes())
                .as("이탈③ 이 어느 필드로 세어지는지 세트가 스스로 적어야 한다")
                .anySatisfy(e -> assertThat(e).contains("no_body_escapes"));
    }

    /**
     * {@code #305} 대조군의 실패 모양으로 스텁 실행을 돌린다.
     *
     * <p>앞 {@value #GENERATION_FAILURES} 건의 생성 호출을 실패시키고, 그중 앞
     * {@value #JUDGE_FAILURES_AMONG_THEM} 건은 InputJudge 호출도 실패시킨다. 실패시키는 방법은
     * 프로덕션이 실제로 받는 것과 같다 — 클라이언트가 {@link RuntimeException} 을 던지고,
     * {@code CellRunner} 와 {@code InputJudge} 가 각자 그것을 잡는다.
     */
    private static CellRunner.Result runWithFailureShape() {
        CellTokenLedger keys = new CellTokenLedger();
        List<UUID> failing = ContractEvalSet.CASES.stream()
                .limit(GENERATION_FAILURES)
                .map(c -> keys.keyFor(c.id()))
                .toList();
        Set<UUID> generationFailures = Set.copyOf(failing);
        Set<UUID> judgeFailures = Set.copyOf(failing.subList(0, JUDGE_FAILURES_AMONG_THEM));

        CellVariant variant = CellVariant.of(BenchmarkCell.A, ContractPromptArm.WITHOUT_CONTRACT_BLOCK);
        try {
            return CellRunner.withClientFactory(variant,
                    CellModelRegistry.resolveForEstimate(BenchmarkCell.A, Map.of()),
                    (ledger, pricing) -> new FailingLlmClient(
                            new StubLlmClient(ledger, pricing), generationFailures, judgeFailures))
                    .run(ContractEvalSet.CASES, false, IDENTITY);
        } catch (Exception e) {
            throw new IllegalStateException("실패 모양 재구성 실행이 죽었다", e);
        }
    }

    /**
     * 지정한 케이스에서 외부 호출을 실패시키는 클라이언트.
     *
     * <p>실패 서명을 {@code #305} 실행의 로그와 같게 둔다 — {@code LLM streaming error} 는 생성
     * 스트리밍, {@code LLM complete error} 는 판정 호출이었다. 케이스 귀속은
     * {@code LlmRequest.userId}(= caseKey) 로 하며, 이는 원장이 쓰는 것과 같은 키다.
     */
    private record FailingLlmClient(LlmClient delegate, Set<UUID> generationFailures,
                                    Set<UUID> judgeFailures) implements LlmClient {

        @Override
        public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
            if (generationFailures.contains(request.userId())) {
                throw new IllegalStateException("LLM streaming error");
            }
            return delegate.stream(request, chunkHandler);
        }

        @Override
        public String completeText(LlmRequest request) {
            return delegate.completeText(request);
        }

        @Override
        public String completeJson(LlmRequest request) {
            // InputJudge 만 실패시킨다. OutputJudge·CBT 분류까지 함께 실패시키면 무엇이 계량기를
            // 가렸는지가 흐려진다 — #305 에서 생성 실패를 덮어쓴 것은 InputJudge 였다.
            if (CellModelRole.INPUT_SAFETY.component().equals(request.component())
                    && judgeFailures.contains(request.userId())) {
                throw new IllegalStateException("LLM complete error");
            }
            return delegate.completeJson(request);
        }
    }

    // ── 실행 도우미 ────────────────────────────────────────────────

    private static ContractComplianceMetrics metricsOf(CellRunner.Result result) {
        return ContractComplianceMetrics.of(result);
    }

    private static CellRunner.Result run(ContractPromptArm arm, PromptSpy spy) {
        return run(arm, spy, null);
    }

    private static CellRunner.Result run(ContractPromptArm arm, PromptSpy spy, String generationText) {
        CellVariant variant = CellVariant.of(BenchmarkCell.A, arm);
        try {
            return CellRunner.withClientFactory(variant,
                    CellModelRegistry.resolveForEstimate(BenchmarkCell.A, Map.of()),
                    (ledger, pricing) -> spy.wrap(new StubLlmClient(ledger, pricing),
                            ledger, pricing, generationText))
                    .run(ContractEvalSet.CASES, false, IDENTITY);
        } catch (Exception e) {
            throw new IllegalStateException("스텁 실행 실패: " + variant.label(), e);
        }
    }

    /** 생성 프롬프트를 그대로 붙잡는 클라이언트. A/B 가 무엇을 갈랐는지는 프롬프트로만 확인된다. */
    private static final class PromptSpy {

        private final ConcurrentLinkedQueue<String> generationPrompts = new ConcurrentLinkedQueue<>();

        List<String> generationPrompts() {
            return List.copyOf(generationPrompts);
        }

        LlmClient wrap(LlmClient delegate, CellTokenLedger ledger, CellPricingBook pricing,
                       String generationText) {
            return new LlmClient() {
                @Override
                public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
                    generationPrompts.add(systemPromptOf(request));
                    if (generationText == null) {
                        return delegate.stream(request, chunkHandler);
                    }
                    chunkHandler.accept(generationText);
                    long completion = CellTokenEstimator.tokens(generationText);
                    long prompt = CellTokenEstimator.promptTokens(request.messages());
                    ledger.writer().write(request.userId(), request.sessionId(), request.component(),
                            request.model(), "stream", prompt, completion, 0L,
                            pricing.costUsd(request.model(), prompt, completion, 0L).orElse(null),
                            OffsetDateTime.now(ZoneOffset.UTC));
                    return new LlmStreamResult(0L,
                            LlmUsage.of(request.model(), prompt, completion), false);
                }

                @Override
                public String completeText(LlmRequest request) {
                    return delegate.completeText(request);
                }

                @Override
                public String completeJson(LlmRequest request) {
                    return delegate.completeJson(request);
                }
            };
        }

        private static String systemPromptOf(LlmRequest request) {
            return request.messages().stream()
                    .filter(m -> "system".equalsIgnoreCase(m.role()))
                    .map(LlmRequest.Message::content)
                    .findFirst()
                    .orElse("");
        }
    }

    /** 검사 대상 어휘가 프로덕션에서 사라지면 이 하네스는 조용히 아무것도 재지 않게 된다. */
    @Test
    @DisplayName("계약 행위 어휘가 프로덕션 enum 과 같다")
    void contractActsMatchProduction() {
        assertThat(ContractEvalSet.CONTRACT_ACTS)
                .allSatisfy(act -> assertThat(ResponseAct.valueOf(act.name())).isEqualTo(act));
        assertThat(ContractOutcome.values())
                .containsExactly(ContractOutcome.NOT_APPLICABLE, ContractOutcome.UNCHECKED,
                        ContractOutcome.PASSED, ContractOutcome.VIOLATED);
    }
}
