package com.mio.ai.qa.correction;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.policy.GenerationMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 시험 세트를 <b>생성 모드 × 팔 × 케이스 × 반복</b> 으로 돌려 응답을 만들고 채점한다 (이슈 #552).
 *
 * <p>작업은 정해진 순서(모드 → 팔 → 케이스 → 반복)로 제출하고 결과도 그 순서로 모은다 — 병렬로
 * 돌려도 같은 입력이 같은 순서의 결과를 낸다. 한 작업의 실패는 그 표본에만 표시되고 실행을 멈추지
 * 않는다(생성·채점 러너가 예외를 삼켜 실패 표본으로 바꾼다).
 *
 * <p>채점기에는 응답만 넘긴다. 모드·팔은 채점 입력에 없다.
 */
final class CorrectionEvalRunner {

    static final String GENERATION_COMPONENT = "CORRECTION_EVAL_GENERATION";
    static final String JUDGE_COMPONENT = "CORRECTION_EVAL_JUDGE";

    /**
     * @param verdict 생성이 실패한 표본은 채점하지 않으므로 {@code null}
     */
    record ScoredSample(String mode, CorrectionGenerationRunner.Sample sample, CorrectionVerdict verdict,
                        CorrectionResponseShape shape) {}

    /**
     * @param budgetStopped 비용 상한에 닿아 남은 표본을 실행하지 않았다. 그 표본은 생성 실패로 표시되므로
     *                      리포트의 실패 비율이 커지고, 이 실행은 인용할 수 없는 실행이 된다
     */
    record Result(CorrectionRunIdentity identity, CorrectionEvalSet set, List<ScoredSample> samples,
                  boolean budgetStopped) {
        Result(CorrectionRunIdentity identity, CorrectionEvalSet set, List<ScoredSample> samples) {
            this(identity, set, samples, false);
        }
    }

    private final LlmClient client;
    private final CorrectionJudge judge;
    private final String generationComponent;

    CorrectionEvalRunner(LlmClient client, CorrectionJudge judge, String generationComponent) {
        this.client = client;
        this.judge = judge;
        this.generationComponent = generationComponent;
    }

    Result run(CorrectionEvalSet set, List<GenerationMode> modes, List<CorrectionPromptArm> arms,
               int repeats, int parallelism, CorrectionRunIdentity identity) {
        return run(set, modes, arms, repeats, parallelism, identity, () -> false);
    }

    /**
     * @param budgetExceeded 각 작업을 시작하기 직전에 확인한다. 참이면 그 작업과 남은 작업은 호출하지 않는다
     */
    Result run(CorrectionEvalSet set, List<GenerationMode> modes, List<CorrectionPromptArm> arms,
               int repeats, int parallelism, CorrectionRunIdentity identity, BooleanSupplier budgetExceeded) {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats 는 1 이상이어야 한다: " + repeats);
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism 은 1 이상이어야 한다: " + parallelism);
        }

        Map<GenerationMode, CorrectionGenerationRunner> runners = new LinkedHashMap<>();
        for (GenerationMode mode : modes) {
            runners.put(mode, CorrectionGenerationRunner.production(client, mode, generationComponent));
        }

        AtomicBoolean stopped = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<ScoredSample>> futures = new ArrayList<>();
            for (GenerationMode mode : modes) {
                for (CorrectionPromptArm arm : arms) {
                    for (CorrectionEvalCase evalCase : set.cases()) {
                        for (int repeat = 0; repeat < repeats; repeat++) {
                            CorrectionGenerationRunner runner = runners.get(mode);
                            int repeatIndex = repeat;
                            futures.add(pool.submit(() -> score(mode, runner, evalCase, arm, repeatIndex,
                                    budgetExceeded, stopped)));
                        }
                    }
                }
            }
            List<ScoredSample> samples = new ArrayList<>();
            for (Future<ScoredSample> future : futures) {
                samples.add(await(future));
            }
            return new Result(identity, set, List.copyOf(samples), stopped.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private ScoredSample score(GenerationMode mode, CorrectionGenerationRunner runner,
                               CorrectionEvalCase evalCase, CorrectionPromptArm arm, int repeat,
                               BooleanSupplier budgetExceeded, AtomicBoolean stopped) {
        if (stopped.get() || budgetExceeded.getAsBoolean()) {
            stopped.set(true);
            return new ScoredSample(mode.name(), new CorrectionGenerationRunner.Sample(
                    evalCase.id(), arm, repeat, "", true, false), null, null);
        }
        CorrectionGenerationRunner.Sample sample = runner.generateOne(evalCase, arm, repeat);
        if (sample.failed()) {
            return new ScoredSample(mode.name(), sample, null, null);
        }
        return new ScoredSample(mode.name(), sample, judge.judge(evalCase, sample.response()),
                CorrectionResponseShape.of(sample.response()));
    }

    private static ScoredSample await(Future<ScoredSample> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("측정 실행이 중단됐다", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("측정 작업이 예상치 못한 예외로 끝났다", e.getCause());
        }
    }
}
