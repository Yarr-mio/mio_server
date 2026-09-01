package com.mio.ai.orchestrator;

import com.mio.ai.crisis.CrisisTrigger;
import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.OutputPreFilterResult;
import com.mio.ai.llm.LlmUsage;
import com.mio.ai.memory.retrieval.MemoryContextResult;
import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.plan.ResponseContractResult;
import com.mio.ai.safety.SafetyL1Result;
import com.mio.ai.security.SecurityAssessment;

/**
 * 한 턴에서 관측된 값들 — {@link AiDecisionLogger} 가 {@code trace} 로 적재하는 입력 전부.
 *
 * <p>이전에는 이 값들이 {@code log()} 의 위치 인자 24개였다. {@code boolean} 과 {@code long} 이
 * 연속으로 붙는 구간이 있어 <b>순서를 바꿔도 컴파일이 통과했고</b>, 잘못된 값이 조용히 적재됐다.
 * 관측값은 사후 분석의 유일한 근거라 틀려도 그 자리에서 드러나지 않는다 — 타입이 아니라 이름으로
 * 묶어야 하는 이유다.
 *
 * <p>{@link Builder} 로만 만든다. 호출부는 채우는 값만 적고 나머지는 기본값을 받는다.
 * 기본값은 "그 일이 일어나지 않았음"을 뜻하며, "모름"과 구별해야 하는 값들은 기본값이
 * {@code null} 이다 — {@link AiDecisionLogger} 가 그 구별을 트레이스에 그대로 옮긴다.
 */
public record TurnObservation(

        // --- 안전 판정 입력 ---
        ModerationResult moderation,
        SafetyL1Result l1Result,
        /** L1 임계값의 출처. {@code null} 이면 {@code "default"} 로 적재된다. */
        String l1ThresholdSource,
        SecurityAssessment securityAssessment,
        boolean inputJudgeCalled,
        /** 판정 결과. {@code null} 은 "귀속 없음"이 아니라 "판정 부재"다. */
        InputJudgeResult inputJudgeResult,
        boolean safetyProfileCacheHit,
        boolean safetyProfileDegraded,

        // --- 기억 ---
        MemoryCacheOutcome memoryCache,
        /** {@code null} 이면 이 턴은 검색을 돌리지 않았다 — 실패와 구별된다. */
        MemoryContextResult memoryContext,

        // --- 지연 (셋을 함께 읽어야 의미가 있다, 이슈 #306) ---
        long totalPipelineMs,
        /** 첫 생성 토큰. 재지 않았으면 음수. */
        long llmTtftMs,
        /** 승인되어 실제로 전달된 첫 콘텐츠. 재지 않았으면 음수. */
        long firstSubstantiveTokenMs,
        /** 사용자가 무언가를 보기까지. 재지 않았으면 음수. */
        long firstRenderedTokenMs,
        /** 서버가 검토된 첫 문장을 먼저 보냈는가 — 위 두 값이 갈리는 이유다. */
        boolean safePrefixApplied,

        // --- 전달·출력 ---
        OutputGuardOutcome outputGuard,
        ResponseContractResult contractResult,
        /** 생성됐지만 위반으로 전달되지 않은 문자 수. */
        int heldBackChars,

        // --- 생성 결과 ---
        /** {@code null} 이면 이 턴은 LLM 을 호출하지 않았다 — 사용량 미확인과 구별된다. */
        LlmUsage llmUsage,
        boolean crisisFlowTriggered,
        /** 실제로 적용된 위기 경로. {@code PolicyDecision} 이 아니라 실행 결과를 따라간다. */
        CrisisTrigger appliedCrisisTrigger
) {

    public static Builder builder(ModerationResult moderation,
                                  SafetyL1Result l1Result,
                                  SecurityAssessment securityAssessment,
                                  long totalPipelineMs) {
        return new Builder(moderation, l1Result, securityAssessment, totalPipelineMs);
    }

    /**
     * 안전 판정 입력과 총 소요시간은 어느 턴에나 있으므로 생성 시점에 받고,
     * 나머지는 해당하는 턴만 채운다.
     */
    public static final class Builder {

        private final ModerationResult moderation;
        private final SafetyL1Result l1Result;
        private final SecurityAssessment securityAssessment;
        private final long totalPipelineMs;

        private String l1ThresholdSource;
        private boolean inputJudgeCalled;
        private InputJudgeResult inputJudgeResult;
        private boolean safetyProfileCacheHit;
        private boolean safetyProfileDegraded;
        private MemoryCacheOutcome memoryCache = MemoryCacheOutcome.live();
        private MemoryContextResult memoryContext;
        private long llmTtftMs = -1;
        private long firstSubstantiveTokenMs = -1;
        private long firstRenderedTokenMs = -1;
        private boolean safePrefixApplied;
        private OutputGuardOutcome outputGuard =
                OutputGuardOutcome.preFilterOnly(OutputPreFilterResult.pass());
        private ResponseContractResult contractResult = ResponseContractResult.notApplicable();
        private int heldBackChars;
        private LlmUsage llmUsage;
        private boolean crisisFlowTriggered;
        private CrisisTrigger appliedCrisisTrigger;

        private Builder(ModerationResult moderation, SafetyL1Result l1Result,
                        SecurityAssessment securityAssessment, long totalPipelineMs) {
            this.moderation = moderation;
            this.l1Result = l1Result;
            this.securityAssessment = securityAssessment;
            this.totalPipelineMs = totalPipelineMs;
        }

        public Builder l1ThresholdSource(String v) { this.l1ThresholdSource = v; return this; }
        public Builder inputJudgeCalled(boolean v) { this.inputJudgeCalled = v; return this; }
        public Builder inputJudgeResult(InputJudgeResult v) { this.inputJudgeResult = v; return this; }
        public Builder safetyProfileCacheHit(boolean v) { this.safetyProfileCacheHit = v; return this; }
        public Builder safetyProfileDegraded(boolean v) { this.safetyProfileDegraded = v; return this; }
        public Builder memoryCache(MemoryCacheOutcome v) { this.memoryCache = v; return this; }
        public Builder memoryContext(MemoryContextResult v) { this.memoryContext = v; return this; }
        public Builder llmTtftMs(long v) { this.llmTtftMs = v; return this; }
        public Builder firstSubstantiveTokenMs(long v) { this.firstSubstantiveTokenMs = v; return this; }
        public Builder firstRenderedTokenMs(long v) { this.firstRenderedTokenMs = v; return this; }
        public Builder safePrefixApplied(boolean v) { this.safePrefixApplied = v; return this; }
        public Builder outputGuard(OutputGuardOutcome v) { this.outputGuard = v; return this; }
        public Builder contractResult(ResponseContractResult v) { this.contractResult = v; return this; }
        public Builder heldBackChars(int v) { this.heldBackChars = v; return this; }
        public Builder llmUsage(LlmUsage v) { this.llmUsage = v; return this; }
        public Builder crisisFlowTriggered(boolean v) { this.crisisFlowTriggered = v; return this; }
        public Builder appliedCrisisTrigger(CrisisTrigger v) { this.appliedCrisisTrigger = v; return this; }

        public TurnObservation build() {
            return new TurnObservation(
                    moderation, l1Result, l1ThresholdSource, securityAssessment,
                    inputJudgeCalled, inputJudgeResult, safetyProfileCacheHit, safetyProfileDegraded,
                    memoryCache, memoryContext,
                    totalPipelineMs, llmTtftMs, firstSubstantiveTokenMs, firstRenderedTokenMs,
                    safePrefixApplied,
                    outputGuard, contractResult, heldBackChars,
                    llmUsage, crisisFlowTriggered, appliedCrisisTrigger);
        }
    }
}
