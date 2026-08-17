package com.mio.ai.llm;

import java.util.UUID;

/**
 * 후보 모델 트래픽 분할 설정 — canary(#480)와 shadow(#481)가 공유한다.
 *
 * <p>둘 다 Redis 값 {@code "<모델> <percent>"} 를 읽고 같은 사용자 버킷 규칙으로 대상을
 * 고른다. 규칙이 갈라지면 "canary 5% 와 shadow 5% 가 같은 사용자인가" 라는 질문에 답할 수
 * 없게 된다 — 한 곳에 두는 이유다.
 */
public record ModelTrafficSplit(String model, int percent) {

    public static final int BUCKETS = 100;

    /**
     * {@code "<모델> <percent>"}. 그 외 모양은 전부 {@code null} — 관대한 파싱은 오타를 삼킨다.
     */
    public static ModelTrafficSplit parse(String raw) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        try {
            int percent = Integer.parseInt(parts[1]);
            if (percent < 0 || percent > BUCKETS) {
                return null;
            }
            return new ModelTrafficSplit(parts[0], percent);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 사용자의 안정 버킷 [0, 100). {@link String#hashCode()} 는 명세에 고정된 알고리즘이라
     * JVM·재시작·인스턴스 간에 같은 사용자가 항상 같은 버킷을 받는다.
     */
    public static int bucketOf(UUID userId) {
        return Math.floorMod(userId.toString().hashCode(), BUCKETS);
    }

    /** 이 사용자가 후보 팔인가. percent 상향 시 기존 후보 팔 사용자는 유지된다(버킷 단조). */
    public boolean selects(UUID userId) {
        return bucketOf(userId) < percent;
    }
}
