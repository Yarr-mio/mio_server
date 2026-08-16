package com.mio.ai.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.input.InputNormalizer;
import com.mio.ai.input.SecurityRuleFilter;
import com.mio.ai.judge.CbtMetadataClassifier;
import com.mio.ai.judge.InputJudge;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.OutputJudge;
import com.mio.ai.judge.OutputJudgeAction;
import com.mio.ai.judge.OutputJudgeResult;
import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmCostCalculator;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.llm.OpenAiLlmClient;
import com.mio.ai.memory.working.WorkingMessage;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.plan.ResponseContractValidator;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.plan.ResponsePlanner;
import com.mio.ai.policy.DecisionAction;
import com.mio.ai.policy.DeliveryMode;
import com.mio.ai.policy.PolicyDecision;
import com.mio.ai.policy.PolicyEngine;
import com.mio.ai.prompt.PromptBuilder;
import com.mio.ai.qa.CellCaseOutcome.Acceptance;
import com.mio.ai.qa.CellCaseOutcome.ContractOutcome;
import com.mio.ai.qa.CellCaseOutcome.Exposure;
import com.mio.ai.qa.LockedEvalSet.LockedCase;
import com.mio.ai.qa.LockedEvalSet.Turn;
import com.mio.ai.safety.CombinedSignal;
import com.mio.ai.safety.SafetyL1;
import com.mio.ai.safety.SafetyL1HistoryMessage;
import com.mio.ai.safety.SafetyL1Input;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.safety.SafetySignalCombiner;
import com.mio.ai.safety.UserMessageSignal;
import com.mio.ai.safety.UserMessageSignalAnalyzer;
import com.mio.ai.security.EffectiveSecurityResolver;
import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityLevel;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 한 셀로 잠금 세트를 돌리는 실행기 (로드맵 §11.3).
 *
 * <h2>무엇을 실제로 돌리는가</h2>
 *
 * <p>{@code CrisisDetectionFullPathQaTest} 가 쓰는 방식과 같다 — 프로덕션 컴포넌트를 그대로
 * 조립해 태운다. 정규화 → 보안 룰 → SafetyL1 → 신호 결합 → InputJudge → PolicyEngine →
 * ResponsePlanner → PromptBuilder → 생성 → OutputPreFilter → 계약 검사 → OutputJudge →
 * CbtMetadataClassifier 까지가 전부 프로덕션 클래스다.
 *
 * <p><b>대신 다음은 이 하네스가 재구성한 것이지 {@code ConversationOrchestrator} 자체가
 * 아니다.</b> 오케스트레이터는 세션·유저 리포지터리와 SSE emitter 를 요구해 DB 없이는 뜨지
 * 않는다. 재구성한 부분과 빠진 부분을 적어 둔다 — 리포트에도 같이 실린다.
 *
 * <ul>
 *   <li>L0 Moderation 은 호출하지 않는다({@link ModerationResult#clear()}). 외부 호출을 하나
 *       더 붙이면 셀 간 차이가 아닌 변동이 들어온다. 모든 셀에 동일하게 적용된다.</li>
 *   <li>메모리 컨텍스트·체크포인트 요약은 비운다. 셀 비교의 변수는 모델과 하네스이지 기억이
 *       아니다.</li>
 *   <li>위기 확정 턴의 고정 문구 생성({@code CrisisFlowService})은 돌리지 않는다. 모델 호출이
 *       없는 경로라 셀을 변별하지 않는다.</li>
 * </ul>
 *
 * <p><b>{@code CbtMetadataClassifier} 는 제외 목록에 없다 — 부른다.</b> 프로덕션이 응답 전달
 * 직후 매 턴 동기로 부르는 실호출이고, 빼면 전 셀이 같은 상수만큼 턴당 원가·지연을 과소
 * 보고한다. 게이트가 비율 문턱이라 그 상수가 경계에서 판정을 뒤집을 수 있다. 호출 조건은
 * {@code sendDoneEvent(..., classifyCbt)} 를 그대로 옮겼다 — 생성한 응답이 실제로 전달된 턴만
 * 부르고, 보안 거절·위기 고정 응답·가드 교체·생성 실패 턴은 부르지 않는다.
 *
 * <h2>스텁 실행</h2>
 *
 * <p>{@link #stubbed} 는 같은 코드 경로를 네트워크 없이 태운다. 견적과 하네스 검증용이며,
 * 판정 값이 고정이라 안전 지표를 주장할 수 없다. 그 사실은 {@link Result#stubMode()} 로
 * 전파되고, 아카이브 기록과 Go/No-Go 산출이 그 값을 보고 막힌다.
 */
final class CellRunner {

    /** 동시 실행 수. 판정용 rate-limit 예산을 생각해 보수적으로 둔다. */
    private static final int PARALLELISM = 4;

    /** 케이스 하나의 제한 시간. 넘기면 그 케이스만 실패로 기록하고 셀은 계속 돈다. */
    private static final long DEFAULT_CASE_TIMEOUT_SECONDS = 180L;

    /**
     * 제한 시간을 줄이기 위한 테스트 seam.
     *
     * <p>실행 때마다 읽는다 — 클래스 초기화 시점에 한 번 읽으면 테스트 실행 순서에 따라 값이
     * 적용되기도 하고 안 되기도 한다.
     */
    static final String CASE_TIMEOUT_PROPERTY = "mio.eval.caseTimeoutSeconds";

    static Duration caseTimeout() {
        String raw = System.getProperty(CASE_TIMEOUT_PROPERTY);
        return Duration.ofSeconds(raw == null || raw.isBlank()
                ? DEFAULT_CASE_TIMEOUT_SECONDS
                : Long.parseLong(raw.trim()));
    }

    /**
     * 생성 출력 상한. {@code ConversationOrchestrator.LLM_MAX_COMPLETION_TOKENS} 와 같은 값이다.
     * 다르게 두면 셀 비용이 프로덕션 비용과 다른 것을 재게 된다.
     */
    private static final int GENERATION_MAX_COMPLETION_TOKENS = 400;

    /** 기본 캐릭터 페르소나. 셀 사이에서 프롬프트 기반이 달라지면 비교가 무의미해진다. */
    private static final String DEFAULT_CHARACTER_ID =
            com.mio.character.domain.CharacterPersona.DEFAULT.characterId();

    /** 이 실행이 무엇을 재구성했는지. manifest 에 그대로 실어 나중에 오해하지 않게 한다. */
    static final String SCOPE = "locked-gold cell benchmark "
            + "(normalizer → security rule → SafetyL1 → combiner → InputJudge → PolicyEngine → "
            + "ResponsePlanner → PromptBuilder → generation → OutputPreFilter → contract → "
            + "OutputJudge → CbtMetadataClassifier; L0 moderation·memory·CrisisFlow 제외)";

    /** 원장별로 클라이언트를 하나씩 만드는 자리. 온라인과 offline reference 가 원장을 나눠 쓴다. */
    interface ClientFactory {
        LlmClient create(CellTokenLedger ledger, CellPricingBook pricing);
    }

    /**
     * 셀 실행 1회의 결과.
     *
     * <p>{@code identity} 는 {@code null} 일 수 없다. 도장 없는 결과를 만들 수 있으면
     * {@link CellGoNoGo} 의 교차 실행 가드가 "도장이 없으니 건너뛴다" 로 우회된다.
     *
     * @param referenceReview 셀 C 의 offline 채점. 온라인 지표와 절대 합산하지 않는다
     */
    record Result(CellVariant variant, CellModelRegistry registry, List<CellCaseOutcome> outcomes,
                  CellTokenLedger ledger, Duration elapsed, boolean stubMode,
                  int population, boolean sampled, RunIdentity identity,
                  Optional<CellReferenceReview> referenceReview, boolean latencyMeasured) {

        Result {
            outcomes = List.copyOf(outcomes);
            if (variant == null) {
                throw new IllegalArgumentException("variant 가 없다 — 어느 셀·어느 후보의 결과인지 모른다");
            }
            if (identity == null) {
                throw new IllegalArgumentException(
                        "실행 도장이 없다 — 같은 실행에서 나온 수치인지 확인할 수 없는 결과는 만들지 않는다");
            }
            if (referenceReview == null) {
                referenceReview = Optional.empty();
            }
        }

        BenchmarkCell cell() {
            return variant.cell();
        }
    }

    private final CellVariant variant;
    private final CellModelRegistry registry;
    private final CellTokenLedger ledger;
    private final ClientFactory clientFactory;
    private final LlmClient llmClient;
    private final boolean stubMode;

    private final InputNormalizer normalizer = new InputNormalizer();
    private final SecurityRuleFilter securityFilter = new SecurityRuleFilter();
    private final SafetyL1 safetyL1 = new SafetyL1(normalizer);
    private final SafetySignalCombiner combiner = new SafetySignalCombiner();
    private final UserMessageSignalAnalyzer signalAnalyzer = new UserMessageSignalAnalyzer();
    private final PolicyEngine policyEngine = new PolicyEngine(new EffectiveSecurityResolver());
    private final ResponsePlanner responsePlanner = new ResponsePlanner();
    private final ResponseContractValidator contractValidator = new ResponseContractValidator();
    private final OutputPreFilter outputPreFilter = new OutputPreFilter();
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final InputJudge inputJudge;
    private final OutputJudge outputJudge;
    private final CbtMetadataClassifier cbtClassifier;

    private CellRunner(CellVariant variant, CellModelRegistry registry, CellTokenLedger ledger,
                       ClientFactory clientFactory, boolean stubMode) {
        this.variant = variant;
        this.registry = registry;
        this.ledger = ledger;
        this.clientFactory = clientFactory;
        this.stubMode = stubMode;
        this.llmClient = new RoleModelRewritingLlmClient(
                clientFactory.create(ledger, registry.pricing()), registry.componentToModel());
        ObjectMapper mapper = new ObjectMapper();
        this.inputJudge = new InputJudge(llmClient, mapper, ledger.meterRegistry());
        this.outputJudge = new OutputJudge(llmClient, mapper, ledger.meterRegistry());
        this.cbtClassifier = new CbtMetadataClassifier(llmClient, mapper);
    }

    /** 실 LLM 실행. 과금된다. */
    static CellRunner realLlm(CellVariant variant, CellModelRegistry registry, String apiKey) {
        return new CellRunner(variant, registry, new CellTokenLedger(),
                (ledger, pricing) -> new OpenAiLlmClient(apiKey, HttpClient.newHttpClient(),
                        new ObjectMapper(), ledger.meterRegistry(),
                        new LlmCostCalculator(pricing.asProperties()), ledger.writer()),
                false);
    }

    /** 네트워크 없는 실행. 견적·하네스 검증 전용이며 판정에 쓸 수 없다. */
    static CellRunner stubbed(CellVariant variant, CellModelRegistry registry) {
        return new CellRunner(variant, registry, new CellTokenLedger(),
                (ledger, pricing) -> new StubLlmClient(ledger, pricing), true);
    }

    /** 단일 셀 편의 진입점. 후보 스크리닝이 아닌 호출부가 그대로 쓴다. */
    static CellRunner stubbed(BenchmarkCell cell, CellModelRegistry registry) {
        return stubbed(CellVariant.of(cell), registry);
    }

    /**
     * 클라이언트를 직접 넣는 실행. 하네스 자신을 검사하는 테스트 전용이다.
     *
     * <p>항상 스텁 모드로 표시된다 — 어떤 클라이언트를 넣었든 프로덕션 모델로 잰 수치가
     * 아니므로, 아카이브와 Go/No-Go 가 막히는 쪽이 맞다.
     */
    static CellRunner withClientFactory(CellVariant variant, CellModelRegistry registry,
                                        ClientFactory factory) {
        return new CellRunner(variant, registry, new CellTokenLedger(), factory, true);
    }

    // ── 실행 ──────────────────────────────────────────────────────

    /**
     * 케이스 목록을 돌린다.
     *
     * <p>입력 순서를 그대로 유지한다. 병렬로 돌리되 결과는 제출 순서로 모으므로, 같은 입력에
     * 같은 순서의 리포트가 나온다 — 실행 간 diff 가 순서 때문에 흔들리면 비교가 불가능하다.
     *
     * <p>온라인 pass 가 끝나 <b>소요 시간이 확정된 뒤에</b> offline reference pass 가 돈다.
     * 순서를 이렇게 두는 것이 셀 C 의 "운영비 증가 없음" 을 구조로 보장하는 방법이다.
     */
    Result run(List<LockedCase> cases, boolean sampled, RunIdentity identity) throws Exception {
        return run(cases, sampled, identity, true);
    }

    /**
     * @param latencyMeasured 이 실행이 지연을 실제로 잰 실행인가. batch 품질 모드는 스트리밍이
     *                        없어 거짓이고, 그러면 리포트가 지연 칸을 숫자 대신
     *                        {@link BatchQualityMode#NOT_MEASURED} 로 찍는다
     */
    Result run(List<LockedCase> cases, boolean sampled, RunIdentity identity,
               boolean latencyMeasured) throws Exception {
        Instant startedAt = Instant.now();
        List<CellCaseOutcome> outcomes = runOnline(cases);
        Duration elapsed = Duration.between(startedAt, Instant.now());
        return new Result(variant, registry, outcomes, ledger, elapsed, stubMode,
                cases.size(), sampled, identity, referenceReview(cases, outcomes), latencyMeasured);
    }

    /**
     * 온라인 pass.
     *
     * <p>한 케이스가 타임아웃되거나 예외로 죽어도 <b>그 케이스만</b> 실패로 기록한다. 예전에는
     * 예외가 그대로 올라가 셀 전체가 중단됐고, 유료 실행의 이미 지출한 부분이 통째로 버려졌다.
     * 외부 장애를 품질 실패와 구별해 세는 기존 규칙({@link Acceptance#REJECTED_EXTERNAL_FAILURE})
     * 을 케이스 단위로도 그대로 적용한다.
     */
    private List<CellCaseOutcome> runOnline(List<LockedCase> cases) {
        ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM);
        try {
            List<Future<CellCaseOutcome>> futures = cases.stream()
                    .map(lockedCase -> pool.submit(() -> evaluate(lockedCase)))
                    .toList();
            List<CellCaseOutcome> outcomes = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                outcomes.add(collect(futures.get(i), cases.get(i)));
                if ((i + 1) % 50 == 0) {
                    System.out.printf("  [%s] 진행 %d/%d%n", variant.label(), i + 1, futures.size());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private CellCaseOutcome collect(Future<CellCaseOutcome> future, LockedCase lockedCase) {
        Duration timeout = caseTimeout();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return failedCase(lockedCase, true,
                    "%d초 타임아웃".formatted(timeout.toSeconds()));
        } catch (ExecutionException e) {
            return failedCase(lockedCase, false,
                    "실행 예외: " + e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedCase(lockedCase, false, "인터럽트");
        }
    }

    /**
     * 실패 케이스의 결과.
     *
     * <p>이미 지출한 호출은 원장에 남아 있으므로 토큰·호출 수를 그대로 싣는다 — 실패했다고
     * 청구서에서 사라지지 않는다. 노출은 {@link Exposure#GUARDED} 로 둔다: 사용자에게 아무것도
     * 전달되지 않았으므로 무검사 노출이 아니고, HARD 위기 케이스라면 고정 플로우도 아니었으므로
     * "가드 하향" 으로 잡힌다 — 실패가 안전 지표에서 <b>공짜 통과</b>가 되지 않는다.
     */
    private CellCaseOutcome failedCase(LockedCase lockedCase, boolean timedOut, String reason) {
        UUID caseKey = ledger.keyFor(lockedCase.id());
        List<CellTokenLedger.Call> calls = ledger.callsFor(caseKey);
        System.out.printf("  [%s] 케이스 실패 %s — %s (셀 전체는 계속 돈다)%n",
                variant.label(), lockedCase.id(), reason);
        return new CellCaseOutcome(
                lockedCase.id(), lockedCase.subgroup(), lockedCase.axis(),
                lockedCase.deterministicLayer(), caseKey,
                CellCaseOutcome.expectedExposure(lockedCase), Exposure.GUARDED,
                lockedCase.expected().safetyTruth(),
                CellCaseOutcome.grade(lockedCase.expected().safetyTruth(), Exposure.GUARDED),
                lockedCase.expected().responseAct(), null,
                CellCaseOutcome.fit(lockedCase.expected().responseAct(), null),
                false, null, false, false, false, false,
                ContractOutcome.NOT_APPLICABLE, List.of(),
                Acceptance.REJECTED_EXTERNAL_FAILURE, timedOut,
                timedOut ? caseTimeout().toMillis() : 0L, 0L,
                calls.size(),
                calls.stream().mapToLong(CellTokenLedger.Call::promptTokens).sum(),
                calls.stream().mapToLong(CellTokenLedger.Call::completionTokens).sum());
    }

    private CellCaseOutcome evaluate(LockedCase lockedCase) {
        long startNanos = System.nanoTime();
        UUID caseKey = ledger.keyFor(lockedCase.id());

        List<Turn> userTurns = lockedCase.userTurns();
        String current = userTurns.get(userTurns.size() - 1).text();
        String normalized = normalizer.normalize(current);
        UserMessageSignal signal = signalAnalyzer.analyze(normalized);
        SecurityAssessment security = securityFilter.check(normalized, current);
        SafetyL1Result l1 = safetyL1.check(new SafetyL1Input(normalized, history(userTurns),
                ModerationResult.clear(), null, signal.emotionScore(), signal.biasType()));
        CombinedSignal combined = combiner.combine(security, l1, ModerationResult.clear(), null);

        boolean judgeCalled = shouldCallJudge(combined);
        InputJudgeResult judgeResult = judgeCalled
                ? inputJudge.judge(normalized, combined, null, caseKey, caseKey)
                : null;

        PolicyDecision decision = policyEngine.decide(combined, judgeResult, null, null);
        decision = decision.withResponsePlan(responsePlanner.plan(decision));

        List<WorkingMessage> priorTurns = priorTurns(userTurns);
        Delivery delivery = deliver(decision, combined, current, priorTurns, caseKey, startNanos);
        boolean cbtCalled = classifyCbt(delivery, current, priorTurns, signal, caseKey);
        return assemble(lockedCase, caseKey, decision, delivery, judgeCalled, judgeResult, cbtCalled,
                delivery.firstSubstantiveMs(), elapsedMs(startNanos));
    }

    /**
     * 프로덕션의 {@code sendDoneEvent(..., classifyCbt)} 를 그대로 옮긴 호출 조건.
     *
     * <p>{@code ConversationOrchestrator} 는 이 값을 {@code true} 로 넘기는 자리가 하나다 —
     * 생성한 응답을 실제로 전달하고 {@code finishedReason="stop"} 으로 끝낸 턴. 보안 거절,
     * 위기 고정 응답, 가드 교체({@code replaced_by_guard}), 생성 실패({@code error}) 는 전부
     * {@code false} 다. 분류기 자신도 응답이 비면 호출 없이 {@code none()} 을 돌려주므로,
     * 조건을 여기서 한 번 더 좁혀 둬야 하네스가 프로덕션보다 많이 부르지 않는다.
     *
     * @return 실제로 분류기를 불렀는가
     */
    private boolean classifyCbt(Delivery delivery, String userMessage,
                                List<WorkingMessage> priorTurns, UserMessageSignal signal,
                                UUID caseKey) {
        boolean deliveredGeneration = delivery.generationCalled()
                && delivery.acceptance() == Acceptance.ACCEPTED
                && delivery.exposure() != Exposure.CRISIS_FLOW
                && delivery.deliveredText() != null
                && !delivery.deliveredText().isBlank();
        if (!deliveredGeneration) {
            return false;
        }
        cbtClassifier.classify(null, priorTurns, userMessage, delivery.deliveredText(),
                signal, 0, false, caseKey, caseKey);
        return true;
    }

    /** 생성 프롬프트에 실을 이전 턴. 멀티턴 케이스의 맥락이 셀 사이에서 달라지면 안 된다. */
    private List<WorkingMessage> priorTurns(List<Turn> userTurns) {
        List<WorkingMessage> history = new ArrayList<>();
        for (int i = 0; i < userTurns.size() - 1; i++) {
            history.add(WorkingMessage.user(userTurns.get(i).text()));
        }
        return history;
    }

    /**
     * Judge 를 부를지 결정한다.
     *
     * <p>{@link BenchmarkCell.HarnessShape#conditionalInputJudge()} 인 셀은 룰이 이미 확정한
     * 턴에서 Judge 를 생략한다. 로드맵 §10.3 cascade 의 "결정론 L0/L1 → 명확한 저위험은
     * 템플릿·경량, 일반 모호성만 경량 Judge" 를 그대로 옮긴 것이다. 룰이 확정한 턴은
     * 위기 확정이거나 보안 공격이므로, Judge 판정이 결과를 바꾸지 않는다.
     */
    private boolean shouldCallJudge(CombinedSignal combined) {
        if (!variant.cell().harness().inputJudgeEnabled()) {
            return false;
        }
        boolean required = inputJudge.shouldCallJudge(combined, null);
        if (!variant.cell().harness().conditionalInputJudge()) {
            return required;
        }
        boolean ruleAlreadyDecided = combined.hardCrisis()
                || combined.securityLevel() == SecurityLevel.ATTACK;
        return required && !ruleAlreadyDecided;
    }

    private List<SafetyL1HistoryMessage> history(List<Turn> userTurns) {
        List<SafetyL1HistoryMessage> history = new ArrayList<>();
        for (int i = 0; i < userTurns.size() - 1; i++) {
            String normalized = normalizer.normalize(userTurns.get(i).text());
            UserMessageSignal signal = signalAnalyzer.analyze(normalized);
            history.add(new SafetyL1HistoryMessage(normalized, signal.emotionScore(), signal.biasType()));
        }
        return history;
    }

    // ── 전달 ──────────────────────────────────────────────────────

    /**
     * 전달 결과.
     *
     * @param deliveredText 사용자에게 전달된 본문. CBT 분류기 입력으로만 쓰고
     *                      {@link CellCaseOutcome} 에는 싣지 않는다 — 결과 타입에 본문이 들어가면
     *                      리포트·아카이브로 새어 나갈 경로가 생긴다
     */
    private record Delivery(Exposure exposure, boolean generationCalled, boolean escalated,
                            boolean outputJudgeCalled, ContractOutcome contract,
                            List<String> contractViolations, Acceptance acceptance,
                            String deliveredText, long firstSubstantiveMs) {}

    /**
     * 정책 결정 이후의 전달 경로.
     *
     * <p>고정 응답 경로(위기·보안 거절)는 모델을 부르지 않는다. 그 사실이 셀별 호출 수와
     * 원가에 그대로 반영돼야 한다 — 위기 케이스를 모델 생성으로 처리하는 셀이 있으면 그건
     * 비용이 아니라 안전 문제다.
     */
    private Delivery deliver(PolicyDecision decision, CombinedSignal combined, String userMessage,
                             List<WorkingMessage> priorTurns, UUID caseKey, long startNanos) {
        if (decision.action() == DecisionAction.CRISIS_FLOW) {
            return new Delivery(Exposure.CRISIS_FLOW, false, false, false,
                    ContractOutcome.NOT_APPLICABLE, List.of(), Acceptance.ACCEPTED, null,
                    elapsedMs(startNanos));
        }
        if (decision.action() == DecisionAction.SECURITY_REFUSAL) {
            return new Delivery(Exposure.SECURITY_REFUSAL, false, false, false,
                    ContractOutcome.NOT_APPLICABLE, List.of(), Acceptance.ACCEPTED, null,
                    elapsedMs(startNanos));
        }
        if (!decision.allowGeneration()) {
            return new Delivery(Exposure.GUARDED, false, false, false,
                    ContractOutcome.NOT_APPLICABLE, List.of(), Acceptance.ACCEPTED, null,
                    elapsedMs(startNanos));
        }
        return generate(decision, combined, userMessage, priorTurns, caseKey, startNanos);
    }

    private Delivery generate(PolicyDecision decision, CombinedSignal combined, String userMessage,
                              List<WorkingMessage> priorTurns, UUID caseKey, long startNanos) {
        ResponsePlan plan = decision.responsePlan();
        Generated generated = callGeneration(decision, plan, userMessage, priorTurns, caseKey,
                CellModelRole.GENERATION, startNanos);
        long firstSubstantiveMs = generated.firstSubstantiveMs();
        if (generated.failed()) {
            return new Delivery(exposureOf(decision), true, false, false,
                    ContractOutcome.NOT_APPLICABLE, List.of(),
                    Acceptance.REJECTED_EXTERNAL_FAILURE, null, firstSubstantiveMs);
        }

        boolean inputHadRiskSignal = combined.riskCandidate() || combined.emotionSpike();
        OutputPreFilterResult preFilter =
                outputPreFilter.checkWithCrisisContext(generated.text(), inputHadRiskSignal);
        ResponseContractResult contract = contractValidator.validate(plan, generated.text());
        ContractOutcome contractOutcome = outcomeOf(contract);

        boolean needsSecondLook = !preFilter.passed() || contractOutcome == ContractOutcome.VIOLATED;
        if (!needsSecondLook) {
            return new Delivery(exposureOf(decision), true, false, false, contractOutcome,
                    contract.violations(), Acceptance.ACCEPTED, generated.text(), firstSubstantiveMs);
        }
        return secondLook(decision, plan, userMessage, priorTurns, caseKey, generated.text(),
                preFilter, contract, firstSubstantiveMs, startNanos);
    }

    /**
     * pre-filter·계약이 걸린 턴의 후속 처리.
     *
     * <p>셀에 따라 셋 중 하나다. (1) 현재 하네스는 OutputJudge 를 부른다. (2) 경량 하네스는
     * 중복 Judge 를 제거했으므로 결정론적 판단으로 끝낸다. (3) escalation 이 켜진 셀은 상위
     * 모델로 한 번 다시 생성한다.
     *
     * <p>어느 경우든 <b>실패는 수용으로 세지 않는다</b> — §11.3 채택 조건 셋째.
     */
    private Delivery secondLook(PolicyDecision decision, ResponsePlan plan, String userMessage,
                                List<WorkingMessage> priorTurns, UUID caseKey, String generatedText,
                                OutputPreFilterResult preFilter, ResponseContractResult contract,
                                long firstSubstantiveMs, long startNanos) {
        ContractOutcome contractOutcome = outcomeOf(contract);
        if (variant.cell().harness().escalationEnabled()) {
            return escalate(decision, plan, userMessage, priorTurns, caseKey, contract,
                    firstSubstantiveMs, startNanos);
        }
        if (!variant.cell().harness().outputJudgeEnabled()) {
            // 중복 Judge 제거 셀 — 결정론적 검사 결과가 최종이다.
            return new Delivery(exposureOf(decision), true, false, false, contractOutcome,
                    contract.violations(),
                    contractOutcome == ContractOutcome.VIOLATED
                            ? Acceptance.REJECTED_CONTRACT
                            : Acceptance.REJECTED_OUTPUT_JUDGE,
                    null, firstSubstantiveMs);
        }

        OutputJudgeResult judged = outputJudge.judge(generatedText, preFilter, caseKey, caseKey);
        Acceptance acceptance = judged.failed()
                ? Acceptance.REJECTED_JUDGE_FAILURE
                : switch (judged.action()) {
                    case SEND, REWRITE -> Acceptance.ACCEPTED;
                    case REPLACE, CRISIS_FLOW -> Acceptance.REJECTED_OUTPUT_JUDGE;
                };
        return new Delivery(judged.action() == OutputJudgeAction.CRISIS_FLOW
                ? Exposure.CRISIS_FLOW : exposureOf(decision),
                true, false, true, contractOutcome, contract.violations(), acceptance,
                acceptance == Acceptance.ACCEPTED ? generatedText : null, firstSubstantiveMs);
    }

    /** 난례를 상위(또는 지정된) 모델로 한 번 다시 생성한다. 회복하지 못하면 수용이 아니다. */
    private Delivery escalate(PolicyDecision decision, ResponsePlan plan, String userMessage,
                              List<WorkingMessage> priorTurns, UUID caseKey,
                              ResponseContractResult contract, long firstSubstantiveMs,
                              long startNanos) {
        CellModelRole role = registry.has(CellModelRole.ESCALATION)
                ? CellModelRole.ESCALATION
                : CellModelRole.GENERATION;
        Generated retry = callGeneration(decision, plan, userMessage, priorTurns, caseKey, role,
                startNanos);
        if (retry.failed()) {
            return new Delivery(exposureOf(decision), true, true, false, outcomeOf(contract),
                    contract.violations(), Acceptance.REJECTED_EXTERNAL_FAILURE, null,
                    firstSubstantiveMs);
        }
        ResponseContractResult retryContract = contractValidator.validate(plan, retry.text());
        ContractOutcome retryOutcome = outcomeOf(retryContract);
        boolean recovered = outputPreFilter.check(retry.text()).passed()
                && retryOutcome != ContractOutcome.VIOLATED;
        return new Delivery(exposureOf(decision), true, true, false, retryOutcome,
                retryContract.violations(),
                recovered ? Acceptance.ACCEPTED : Acceptance.REJECTED_CONTRACT,
                recovered ? retry.text() : null, firstSubstantiveMs);
    }

    private record Generated(String text, long firstSubstantiveMs, boolean failed) {}

    private Generated callGeneration(PolicyDecision decision, ResponsePlan plan, String userMessage,
                                     List<WorkingMessage> priorTurns, UUID caseKey,
                                     CellModelRole role, long startNanos) {
        String systemPrompt = promptBuilder.buildSystemPrompt(decision.generationMode(),
                decision.interventionHints(), null, DEFAULT_CHARACTER_ID, null, plan);
        LlmRequest request = LlmRequest.of(registry.modelFor(role), systemPrompt,
                        priorTurns, userMessage)
                .withMaxCompletionTokens(GENERATION_MAX_COMPLETION_TOKENS)
                .withAttribution(role.component(), caseKey, caseKey);
        StringBuilder content = new StringBuilder();
        long beforeCallMs = elapsedMs(startNanos);
        try {
            LlmStreamResult result = llmClient.stream(request, content::append);
            return new Generated(content.toString(),
                    beforeCallMs + Math.max(result.ttftMs(), 0), false);
        } catch (RuntimeException e) {
            // 외부 장애는 품질 실패와 다르게 센다 — 섞으면 모델 비교가 네트워크 비교가 된다.
            return new Generated("", elapsedMs(startNanos), true);
        }
    }

    // ── offline reference pass ────────────────────────────────────

    /**
     * 셀 C 의 offline 채점.
     *
     * <p>{@link CellModelRole#REFERENCE_JUDGE} 를 선언하지 않은 셀은 아무것도 하지 않는다.
     * 자기 원장·자기 클라이언트를 새로 만들어 넘기므로, 이 pass 의 토큰과 비용이 온라인
     * 집계에 더해질 경로가 코드상 존재하지 않는다.
     */
    private Optional<CellReferenceReview> referenceReview(List<LockedCase> cases,
                                                          List<CellCaseOutcome> outcomes) {
        if (!registry.has(CellModelRole.REFERENCE_JUDGE)) {
            return Optional.empty();
        }
        CellTokenLedger offlineLedger = new CellTokenLedger();
        LlmClient offlineClient = clientFactory.create(offlineLedger, registry.pricing());
        return Optional.of(CellReferenceJudge.review(
                registry.modelFor(CellModelRole.REFERENCE_JUDGE), offlineClient, offlineLedger,
                registry.pricing(), cases, outcomes, PARALLELISM));
    }

    // ── 조립 ──────────────────────────────────────────────────────

    private Exposure exposureOf(PolicyDecision decision) {
        return decision.deliveryMode() == DeliveryMode.SPECULATIVE
                ? Exposure.UNGUARDED
                : Exposure.GUARDED;
    }

    private ContractOutcome outcomeOf(ResponseContractResult result) {
        if (!result.applicable()) {
            return ContractOutcome.NOT_APPLICABLE;
        }
        if (!result.checked()) {
            return ContractOutcome.UNCHECKED;
        }
        return result.passed() ? ContractOutcome.PASSED : ContractOutcome.VIOLATED;
    }

    private CellCaseOutcome assemble(LockedCase lockedCase, UUID caseKey, PolicyDecision decision,
                                     Delivery delivery, boolean judgeCalled,
                                     InputJudgeResult judgeResult, boolean cbtClassifierCalled,
                                     long firstSubstantiveMs, long totalMs) {
        List<CellTokenLedger.Call> calls = ledger.callsFor(caseKey);
        return new CellCaseOutcome(
                lockedCase.id(), lockedCase.subgroup(), lockedCase.axis(),
                lockedCase.deterministicLayer(), caseKey,
                CellCaseOutcome.expectedExposure(lockedCase), delivery.exposure(),
                lockedCase.expected().safetyTruth(),
                CellCaseOutcome.grade(lockedCase.expected().safetyTruth(), delivery.exposure()),
                lockedCase.expected().responseAct(), decision.responsePlan().responseAct(),
                CellCaseOutcome.fit(lockedCase.expected().responseAct(),
                        decision.responsePlan().responseAct()),
                judgeCalled, decision.judgeStatus(), delivery.generationCalled(),
                delivery.escalated(), delivery.outputJudgeCalled(), cbtClassifierCalled,
                delivery.contract(), delivery.contractViolations(),
                judgeResult != null && judgeResult.failed()
                        ? Acceptance.REJECTED_JUDGE_FAILURE : delivery.acceptance(),
                false, totalMs, firstSubstantiveMs, calls.size(),
                calls.stream().mapToLong(CellTokenLedger.Call::promptTokens).sum(),
                calls.stream().mapToLong(CellTokenLedger.Call::completionTokens).sum());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
