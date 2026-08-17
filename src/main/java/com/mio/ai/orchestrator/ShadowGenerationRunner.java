package com.mio.ai.orchestrator;

import com.mio.ai.judge.OutputPreFilter;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.ModelTrafficSplit;
import com.mio.ai.memory.consolidation.MemoryConsentChecker;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 생성 모델 shadow 실행기 (#481).
 *
 * <p>P0-8 잔여 게이트 "shadow 트래픽 회귀 확인"의 실행 지점이다. 샘플된 실 사용자 턴의
 * 생성 요청을 후보 모델에 비동기 복제 호출하고, 결과는 <b>절대 전달하지 않고</b> 파생
 * 판정만 계측한다.
 *
 * <pre>
 * SET mio:ai:shadow:generation "gpt-4.1-nano 5"   # 5% 사용자 shadow
 * DEL mio:ai:shadow:generation                     # 종료
 * </pre>
 *
 * <p><b>본 응답 경로에 아무 비용도 지연도 더하지 않는다.</b> 호출 스레드에서 하는 일은
 * Redis GET 한 번과 태스크 제출뿐이고, LLM 호출·동의 조회는 전부 비동기 태스크 안이다.
 * 어떤 실패(Redis 장애·executor 포화·그림자 호출 실패)도 본 턴으로 전파되지 않는다.
 *
 * <p><b>기록하는 것은 파생 판정뿐이다.</b> 그림자 본문은 저장하지 않는다 — 본문 저장은
 * 프라이버시 검토(로드맵 §10.4) 뒤의 후속이다. 남는 것: {@code mio.model.shadow} 카운터
 * (사전 필터 통과/위반이 핵심 신호), 그리고 {@code SHADOW_GENERATION} 귀속으로
 * 기존 {@code mio_llm_*} 지표·비용 이벤트에 실리는 토큰·지연·비용.
 *
 * <p>게이트는 canary 와 같다: allowlist 밖·단가 미등록 후보는 무시(fail-safe), 동의 철회
 * 사용자는 제외(추가 전송은 필수가 아니다 — 동의 없이 나가면 P0-6 게이트를 우회한다).
 * 버킷 규칙도 canary 와 공유해({@link ModelTrafficSplit}) "canary 5% 와 shadow 5% 가
 * 같은 사용자인가"에 답할 수 있다.
 */
@Component
@Slf4j
public class ShadowGenerationRunner {

    static final String SHADOW_KEY = "mio:ai:shadow:generation";
    private static final String METRIC = "mio.model.shadow";
    private static final String COMPONENT = "SHADOW_GENERATION";

    private final ModelCatalog modelCatalog;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final LlmClient llmClient;
    private final OutputPreFilter outputPreFilter;
    private final MemoryConsentChecker memoryConsentChecker;
    private final Executor shadowExecutor;

    public ShadowGenerationRunner(ModelCatalog modelCatalog,
                                  StringRedisTemplate redisTemplate,
                                  MeterRegistry meterRegistry,
                                  LlmClient llmClient,
                                  OutputPreFilter outputPreFilter,
                                  MemoryConsentChecker memoryConsentChecker,
                                  @Qualifier("shadowGenerationExecutor") Executor shadowExecutor) {
        this.modelCatalog = modelCatalog;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.llmClient = llmClient;
        this.outputPreFilter = outputPreFilter;
        this.memoryConsentChecker = memoryConsentChecker;
        this.shadowExecutor = shadowExecutor;
    }

    /** 본 생성 요청을 받아 샘플이면 그림자 태스크를 제출한다. 어떤 경우에도 던지지 않는다. */
    public void maybeShadow(LlmRequest primary) {
        try {
            String raw = redisTemplate.opsForValue().get(SHADOW_KEY);
            if (raw == null || raw.isBlank()) {
                return;
            }
            ModelTrafficSplit shadow = ModelTrafficSplit.parse(raw);
            if (shadow == null || !modelCatalog.isAllowed(shadow.model())
                    || !modelCatalog.isPriced(shadow.model())) {
                count("invalid_config");
                log.warn("shadow 설정이 유효하지 않아 무시: '{}'", raw);
                return;
            }
            if (shadow.model().equals(primary.model())) {
                // canary 가 같은 후보를 이미 본 응답으로 태우는 사용자다 — 여기서 또 부르면
                // 지출만 2배고 신호는 0 이다 (본 경로가 같은 모델에 이미 사전 필터를 돈다).
                // 겹침을 대시보드에서 보이게 세고 건너뛴다.
                count("skipped_duplicate_of_primary");
                return;
            }
            if (shadow.percent() == 0 || !shadow.selects(primary.userId())) {
                return;
            }
            LlmRequest shadowRequest = new LlmRequest(shadow.model(), primary.messages(),
                    primary.maxCompletionTokens(), COMPONENT, primary.userId(),
                    primary.sessionId());
            shadowExecutor.execute(() -> runShadow(shadowRequest));
        } catch (RejectedExecutionException e) {
            // executor 포화 — shadow 가 밀리면 버린다. 본 턴을 기다리게 하는 선택지는 없다.
            count("rejected");
        } catch (RuntimeException e) {
            count("error");
            log.warn("shadow 설정 조회 실패, 이 턴은 건너뜀: {}", e.getMessage());
        }
    }

    private void runShadow(LlmRequest shadowRequest) {
        try {
            // 동의 조회는 DB 를 본다 — 호출 스레드가 아니라 여기서 한다. 조회 실패는
            // MemoryConsentChecker 가 철회로 취급하므로(fail-closed) 그대로 따른다.
            if (!memoryConsentChecker.isRetentionAllowed(shadowRequest.userId())) {
                count("consent_excluded");
                return;
            }
            String text = llmClient.completeText(shadowRequest);
            boolean passed = outputPreFilter.check(text).passed();
            count(passed ? "prefilter_passed" : "prefilter_failed");
        } catch (RuntimeException e) {
            count("shadow_failed");
            log.warn("shadow 호출 실패 (본 턴 영향 없음): {}", e.getMessage());
        }
    }

    private void count(String outcome) {
        meterRegistry.counter(METRIC, "outcome", outcome).increment();
    }
}
