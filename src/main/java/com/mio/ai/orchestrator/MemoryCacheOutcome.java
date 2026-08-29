package com.mio.ai.orchestrator;

/**
 * 기억 문맥을 캐시 폴백으로 채웠는지, 그 문맥이 얼마나 낡았는지 (이슈 #522).
 *
 * <p>두 값을 함께 묶는다. 따로 넘기면 {@code null} 나이가 두 가지를 뜻하게 된다 —
 * <b>폴백을 쓰지 않았다</b> 와 <b>폴백을 썼지만 TTL 을 읽지 못했다</b>. 이 항목이 만들려는
 * 관측이 바로 폴백 경로의 품질이므로, 그 둘을 구별하지 못하면 계측이 무의미하다.
 *
 * @param fallbackUsed 라이브 검색이 비어 캐시를 채택했는가
 * @param stalenessMs  채택한 캐시의 나이(ms). 폴백을 쓰지 않았거나 TTL 을 읽지 못하면 {@code null}
 */
public record MemoryCacheOutcome(boolean fallbackUsed, Long stalenessMs) {

    private static final MemoryCacheOutcome LIVE = new MemoryCacheOutcome(false, null);

    /** 라이브 검색으로 채웠다 — 폴백을 쓰지 않았다. */
    public static MemoryCacheOutcome live() {
        return LIVE;
    }

    /** 캐시를 채택했다. {@code ageMs} 가 {@code null} 이면 나이를 읽지 못한 것이다. */
    public static MemoryCacheOutcome fallback(Long ageMs) {
        return new MemoryCacheOutcome(true, ageMs);
    }
}
