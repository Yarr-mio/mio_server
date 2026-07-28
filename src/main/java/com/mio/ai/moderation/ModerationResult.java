package com.mio.ai.moderation;

import java.util.Map;

/**
 * L0 Moderation 판정 결과.
 *
 * @param resolved 판정을 실제로 받아왔는지 (이슈 #263).
 *                 {@code false} 면 나머지 필드는 판정 결과가 아니라 fail-open 폴백값이다.
 *                 이 구분이 없으면 "위험 신호 없음"과 "안전 계층이 통째로 빠진 상태"가 같은 값이 된다.
 */
public record ModerationResult(
        boolean resolved,
        boolean flagged,
        Map<String, Boolean> categories,
        Map<String, Double> categoryScores
) {
    public ModerationResult {
        categories = categories != null ? Map.copyOf(categories) : Map.of();
        categoryScores = categoryScores != null ? Map.copyOf(categoryScores) : Map.of();
    }

    /** 조회 성공 여부 개념 도입 이전 시그니처 — 기존 호출부 호환용 (이슈 #263). */
    public ModerationResult(
            boolean flagged,
            Map<String, Boolean> categories,
            Map<String, Double> categoryScores) {
        this(true, flagged, categories, categoryScores);
    }

    /** 판정을 받아왔고 아무 카테고리도 걸리지 않았다. */
    public static ModerationResult clear() {
        return new ModerationResult(true, false, Map.of(), Map.of());
    }

    /**
     * 판정을 받아오지 못했다.
     *
     * <p>fail-open 자체는 타당한 설계다 — 하드 실패로 바꾸면 Moderation API 장애가 곧
     * 서비스 중단이 된다. 다만 그 상태가 값에 남아야 사후에 구분할 수 있다.
     */
    public static ModerationResult failOpen() {
        return new ModerationResult(false, false, Map.of(), Map.of());
    }

    public boolean isSelfHarmFlagged() {
        return Boolean.TRUE.equals(categories.get("self-harm"))
                || Boolean.TRUE.equals(categories.get("self-harm/intent"))
                || Boolean.TRUE.equals(categories.get("self-harm/instructions"));
    }

    public double selfHarmScore() {
        return categoryScores.getOrDefault("self-harm", 0.0);
    }
}
