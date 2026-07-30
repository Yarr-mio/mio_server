package com.mio.ai.security;

import com.mio.ai.judge.InputJudgeResult;
import com.mio.ai.judge.SecurityVerdict;
import org.springframework.stereotype.Component;

/**
 * 규칙 판정과 Judge 판정을 합쳐 실제로 적용할 보안 등급을 정한다 (이슈 #262).
 *
 * <p>이 클래스가 생긴 이유는 <b>Judge 의 보안 판정이 아무 데서도 소비되지 않았기 때문</b>이다.
 * 프롬프트에 스키마를 정의하고 토큰을 써서 받아온 뒤 버렸다. 비용은 내면서 효과는 없고,
 * 코드를 읽는 사람에게는 "보안 판정이 이중으로 걸려 있다"는 잘못된 안심을 줬다.
 *
 * <h2>결합 규칙</h2>
 * <table>
 *   <tr><th>규칙 판정</th><th>Judge 판정</th><th>결과</th></tr>
 *   <tr><td>ATTACK</td><td>—</td><td>ATTACK (Judge 미호출)</td></tr>
 *   <tr><td>SUSPICIOUS</td><td>CLEAN</td><td>CLEAN (오탐 복구)</td></tr>
 *   <tr><td>SUSPICIOUS</td><td>SUSPICIOUS 이상</td><td>SUSPICIOUS</td></tr>
 *   <tr><td>SUSPICIOUS</td><td><b>실패·부재</b></td><td><b>SUSPICIOUS 유지</b></td></tr>
 *   <tr><td>CLEAN</td><td>SUSPICIOUS 이상</td><td>SUSPICIOUS</td></tr>
 *   <tr><td>CLEAN</td><td>실패·부재</td><td>CLEAN</td></tr>
 * </table>
 *
 * <p><b>핵심은 Judge 가 답을 주지 못했을 때 규칙 판정을 낮추지 않는 것이다.</b> 이 시리즈에서
 * 반복해 틀렸던 지점이 정확히 여기다 — 판정하지 못한 것을 "괜찮다"로 처리하면, 안전 계층을
 * 하나 더 만들어놓고 그 계층의 실패가 기존 보호를 걷어내는 구조가 된다.
 *
 * <p><b>ATTACK 확정은 규칙만 할 수 있다.</b> Judge 는 최대 {@code SUSPICIOUS} 까지만 올린다.
 * ATTACK 은 본문 생성을 막고 고정 거절 응답을 내보내므로 오탐 비용이 크고, LLM 판정은
 * 그 정도의 확신을 담보하지 못한다. SUSPICIOUS 는 생성을 허용하고 출력 가드를 켜므로
 * 오탐이어도 대화가 끊기지 않는다.
 */
@Component
public class EffectiveSecurityResolver {

    /**
     * @param ruleLevel   {@code SecurityRuleFilter} 의 결정론적 판정
     * @param judgeResult Judge 결과. {@code null} 이면 호출되지 않았고,
     *                    {@code failed()} 면 호출했지만 판정을 받지 못했다
     */
    public SecurityLevel resolve(SecurityLevel ruleLevel, InputJudgeResult judgeResult) {
        if (ruleLevel == SecurityLevel.ATTACK) {
            return SecurityLevel.ATTACK;
        }

        SecurityLevel judgeLevel = judgeLevelOf(judgeResult);
        if (judgeLevel == null) {
            // 판정을 못 받았다. 규칙 판정을 그대로 유지한다 — 낮추지 않는다.
            return ruleLevel;
        }

        if (ruleLevel == SecurityLevel.SUSPICIOUS) {
            // Judge 가 명시적으로 CLEAN 이라고 한 경우에만 오탐을 복구한다.
            return judgeLevel == SecurityLevel.CLEAN ? SecurityLevel.CLEAN : SecurityLevel.SUSPICIOUS;
        }

        // 규칙이 CLEAN — Judge 가 의심하면 올린다. 단 ATTACK 까지는 올리지 않는다.
        return judgeLevel == SecurityLevel.CLEAN ? SecurityLevel.CLEAN : SecurityLevel.SUSPICIOUS;
    }

    /**
     * @return Judge 가 실제로 내린 보안 등급. 호출되지 않았거나 판정 실패면 {@code null}
     */
    private SecurityLevel judgeLevelOf(InputJudgeResult judgeResult) {
        if (judgeResult == null || judgeResult.failed()) {
            return null;
        }
        SecurityVerdict verdict = judgeResult.security();
        return verdict != null ? verdict.level() : null;
    }
}
