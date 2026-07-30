package com.mio.ai.security;

import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.RiskVerdict;
import com.mio.ai.judge.SecurityVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveSecurityResolverTest {

    private final EffectiveSecurityResolver resolver = new EffectiveSecurityResolver();

    @Test
    @DisplayName("규칙이 ATTACK 이면 Judge 와 무관하게 ATTACK — 확정은 규칙만 한다")
    void ruleAttackIsFinal() {
        assertThat(resolver.resolve(SecurityLevel.ATTACK, judge(SecurityLevel.CLEAN)))
                .isEqualTo(SecurityLevel.ATTACK);
        assertThat(resolver.resolve(SecurityLevel.ATTACK, null))
                .isEqualTo(SecurityLevel.ATTACK);
    }

    @Test
    @DisplayName("규칙 SUSPICIOUS + Judge CLEAN → CLEAN (오탐 복구)")
    void judgeClearsRuleFalsePositive() {
        assertThat(resolver.resolve(SecurityLevel.SUSPICIOUS, judge(SecurityLevel.CLEAN)))
                .as("이 복구가 없으면 '관리자 권한을 못 받아서' 같은 발화가 계속 가드에 걸린다")
                .isEqualTo(SecurityLevel.CLEAN);
    }

    @Test
    @DisplayName("규칙 SUSPICIOUS + Judge 판정 실패 → SUSPICIOUS 유지")
    void judgeFailureDoesNotLowerRuleVerdict() {
        assertThat(resolver.resolve(SecurityLevel.SUSPICIOUS, InputJudgeResult.fallback()))
                .as("판정하지 못한 것을 '깨끗하다'로 처리하면 없는 근거로 등급을 낮추는 것이다")
                .isEqualTo(SecurityLevel.SUSPICIOUS);
    }

    @Test
    @DisplayName("규칙 SUSPICIOUS + Judge 미호출 → SUSPICIOUS 유지")
    void judgeAbsenceDoesNotLowerRuleVerdict() {
        assertThat(resolver.resolve(SecurityLevel.SUSPICIOUS, null))
                .isEqualTo(SecurityLevel.SUSPICIOUS);
    }

    @Test
    @DisplayName("규칙 SUSPICIOUS + Judge ATTACK → SUSPICIOUS (Judge 는 ATTACK 까지 못 올린다)")
    void judgeCannotEscalateToAttack() {
        assertThat(resolver.resolve(SecurityLevel.SUSPICIOUS, judge(SecurityLevel.ATTACK)))
                .as("ATTACK 은 본문 생성을 막고 거절하므로 오탐 비용이 크다. LLM 판정에 맡기지 않는다")
                .isEqualTo(SecurityLevel.SUSPICIOUS);
        assertThat(resolver.resolve(SecurityLevel.CLEAN, judge(SecurityLevel.ATTACK)))
                .isEqualTo(SecurityLevel.SUSPICIOUS);
    }

    @Test
    @DisplayName("규칙 CLEAN + Judge SUSPICIOUS → SUSPICIOUS (규칙이 놓친 변형을 Judge 가 잡는다)")
    void judgeRaisesWhatRulesMissed() {
        assertThat(resolver.resolve(SecurityLevel.CLEAN, judge(SecurityLevel.SUSPICIOUS)))
                .as("이 경로가 없으면 Judge 판정은 파싱만 되고 버려진다 — 이슈 #262 의 본문")
                .isEqualTo(SecurityLevel.SUSPICIOUS);
    }

    @Test
    @DisplayName("규칙 CLEAN + Judge CLEAN·실패·미호출 → CLEAN")
    void cleanStaysClean() {
        assertThat(resolver.resolve(SecurityLevel.CLEAN, judge(SecurityLevel.CLEAN)))
                .isEqualTo(SecurityLevel.CLEAN);
        assertThat(resolver.resolve(SecurityLevel.CLEAN, InputJudgeResult.fallback()))
                .isEqualTo(SecurityLevel.CLEAN);
        assertThat(resolver.resolve(SecurityLevel.CLEAN, null))
                .isEqualTo(SecurityLevel.CLEAN);
    }

    private InputJudgeResult judge(SecurityLevel level) {
        return new InputJudgeResult(
                new SecurityVerdict(level, List.of(), false),
                RiskVerdict.clearLow(),
                0.9);
    }
}
