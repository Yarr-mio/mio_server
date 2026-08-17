package com.mio.ai.llm;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 생성 모델 canary 라우팅 (#480).
 *
 * <p>운영자가 Redis 키 하나로 후보 모델을 일부 트래픽에 태운다.
 *
 * <pre>
 * SET mio:ai:canary:generation "gpt-4.1-nano 5"   # 후보 5%
 * SET mio:ai:canary:generation "gpt-4.1-nano 0"   # 즉시 롤백 (새 턴부터)
 * DEL mio:ai:canary:generation                     # canary 종료
 * </pre>
 *
 * <p>턴마다 키를 읽으므로 변경은 재배포 없이 다음 턴부터 반영된다. 팔 배정은 사용자 ID 의
 * 안정 해시 버킷이라 같은 사용자는 세션이 바뀌어도 같은 팔이고, percent 를 올릴 때 기존
 * 후보 팔 사용자가 기본 팔로 튀지 않는다(버킷 단조).
 *
 * <p><b>안전 성질 — 확신이 없으면 기본 모델이다.</b> 설정 없음·파싱 실패·범위 밖 percent·
 * allowlist 밖 후보·단가 미등록 후보·Redis 장애 전부 카탈로그 기본 해석으로 떨어지고
 * {@code mio.model.canary} 카운터에 남는다. canary 는 검증된 후보를 태우는 장치이지,
 * 장애나 오타가 미검증 모델로 새는 통로가 아니다. allowlist 등재는 PR 로만 하므로
 * 이 키로는 allowlist 밖 모델을 절대 태울 수 없다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerationCanaryRouter {

    static final String CANARY_KEY = "mio:ai:canary:generation";
    private static final String METRIC = "mio.model.canary";
    private static final int BUCKETS = 100;

    private final ModelCatalog modelCatalog;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    /** 이 턴의 생성 모델. canary 미설정·불신 시 카탈로그의 {@code GENERATION} 해석이다. */
    public String modelFor(UUID userId) {
        String defaultModel = modelCatalog.modelFor(ModelRole.GENERATION);
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(CANARY_KEY);
        } catch (RuntimeException e) {
            // 라우팅을 '모른다'가 미검증 모델이 되면 안 된다 — 기본 팔로 간다.
            count("error");
            log.warn("canary 설정 조회 실패, 기본 모델로 라우팅: {}", e.getMessage());
            return defaultModel;
        }
        if (raw == null || raw.isBlank()) {
            count("default");
            return defaultModel;
        }

        Canary canary = parse(raw);
        if (canary == null || !modelCatalog.isAllowed(canary.model())
                || !modelCatalog.isPriced(canary.model())) {
            count("invalid_config");
            log.warn("canary 설정이 유효하지 않아 기본 모델로 라우팅: '{}'", raw);
            return defaultModel;
        }
        if (bucketOf(userId) < canary.percent()) {
            count("candidate");
            return canary.model();
        }
        count("default");
        return defaultModel;
    }

    /** {@code "<모델> <percent>"}. 그 외 모양은 전부 무효 — 관대한 파싱은 오타를 삼킨다. */
    private static Canary parse(String raw) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        try {
            int percent = Integer.parseInt(parts[1]);
            if (percent < 0 || percent > BUCKETS) {
                return null;
            }
            return new Canary(parts[0], percent);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 사용자의 안정 버킷 [0, 100). {@link String#hashCode()} 는 명세에 고정된 알고리즘이라
     * JVM·재시작·인스턴스 간에 같은 사용자가 항상 같은 버킷을 받는다.
     */
    private static int bucketOf(UUID userId) {
        return Math.floorMod(userId.toString().hashCode(), BUCKETS);
    }

    private void count(String outcome) {
        meterRegistry.counter(METRIC, "outcome", outcome).increment();
    }

    private record Canary(String model, int percent) {}
}
