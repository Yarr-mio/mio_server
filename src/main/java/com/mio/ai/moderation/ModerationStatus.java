package com.mio.ai.moderation;

/**
 * L0 Moderation이 이번 턴의 정책 결정에 제공한 판정 상태 (이슈 #294).
 *
 * <p>{@code JudgeStatus}와 같은 이유로 존재한다 — 판정 부재를 "위험 없음"으로 축약하면
 * 안전 계층 하나가 통째로 빠진 턴이 정상 판정 턴과 같은 값을 갖게 된다. Moderation은 매 턴
 * 호출되므로 생략 상태는 없고, 받아왔는지 아닌지 두 가지만 구분한다.
 */
public enum ModerationStatus {
    /** 판정을 받아왔다. {@code flagged} 는 실제 판정 결과다. */
    RESOLVED,
    /** 판정을 받아오지 못했다(fail-open). {@code flagged=false} 는 판정 결과가 아니다. */
    UNRESOLVED
}
