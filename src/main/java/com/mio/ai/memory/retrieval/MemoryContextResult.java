package com.mio.ai.memory.retrieval;

import java.util.EnumSet;
import java.util.Set;

/**
 * 메모리 컨텍스트와 그것을 만드는 데 실패한 소스들 (이슈 #364, 로드맵 §12 P0-2).
 *
 * <p>이전에는 {@code String} 하나만 돌려줬다. 그래서 검색기가 예외를 삼키고 빈 목록을
 * 반환하면 호출부는 물론 trace 에도 그 사실이 남지 않았고, DB 장애가 난 턴이 "기억이
 * 없는 사용자" 와 완전히 동일하게 보였다.
 *
 * <p><b>위험도는 여기서 바꾸지 않는다.</b> 판정 부재로 위험 등급을 만들면 오탐이 늘고
 * 그 등급이 다시 다른 분기의 입력이 된다 — 이슈 {@code #294} 가 L0 에서 정한 원칙과 같다.
 * 이 타입이 하는 일은 실패를 <b>보이게</b> 만드는 것뿐이다.
 */
public record MemoryContextResult(
        String text,
        MemoryContextStatus status,
        Set<RetrievalSource> failedSources
) {

    public MemoryContextResult {
        failedSources = failedSources == null || failedSources.isEmpty()
                ? Set.of()
                : Set.copyOf(failedSources);
    }

    public static MemoryContextResult ok(String text) {
        return new MemoryContextResult(text, MemoryContextStatus.OK, Set.of());
    }

    /**
     * 일부 소스가 죽었지만 남은 것으로 컨텍스트를 만든 경우.
     *
     * <p>실패 집합이 비어 있으면 {@link #ok(String)} 와 같다 — 호출부가 조건 분기를
     * 하지 않아도 되도록 여기서 흡수한다.
     */
    public static MemoryContextResult partial(String text, Set<RetrievalSource> failedSources) {
        if (failedSources == null || failedSources.isEmpty()) {
            return ok(text);
        }
        return new MemoryContextResult(text, MemoryContextStatus.PARTIAL, failedSources);
    }

    /** 컨텍스트 조립 자체가 실패했다. 텍스트는 없지만 "없음" 과 구별된다. */
    public static MemoryContextResult failed() {
        return new MemoryContextResult(null, MemoryContextStatus.FAILED, Set.of());
    }

    /** 캐시에서 읽어온 컨텍스트. 이번 턴에 검색을 돌리지 않았으므로 실패도 없다. */
    public static MemoryContextResult cached(String text) {
        return ok(text);
    }

    public boolean degraded() {
        return status != MemoryContextStatus.OK;
    }

    /** trace 용 정렬된 표기. 실행마다 순서가 흔들리면 로그를 비교할 수 없다. */
    public String failedSourcesLabel() {
        if (failedSources.isEmpty()) {
            return null;
        }
        return EnumSet.copyOf(failedSources).stream()
                .map(Enum::name)
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
