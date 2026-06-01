package com.mio.ai.judge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class OutputPreFilter {

    // ROLE_BOUNDARY: AI claiming professional roles
    private static final Set<String> ROLE_BOUNDARY_PATTERNS = Set.of(
            "저는 의사", "저는 상담사", "저는 정신과", "저는 심리치료사",
            "의사로서", "상담사로서", "치료사로서", "전문가로서 진단",
            "i am a doctor", "i am a therapist", "as a psychiatrist"
    );

    // DIAGNOSIS_CLAIM: Medical diagnosis or prescription
    private static final Set<String> DIAGNOSIS_PATTERNS = Set.of(
            "당신은 우울증", "당신은 불안장애", "당신은 ptsd", "당신에게는 약",
            "진단을 내리", "처방해드릴", "항우울제", "정신과 약",
            "you have depression", "you have anxiety disorder", "you should take medication"
    );

    // DEPENDENCY_REINFORCE: Dependency-reinforcing phrases
    private static final Set<String> DEPENDENCY_PATTERNS = Set.of(
            "나 없이는 안 돼", "나만 있으면 돼", "내가 항상 곁에 있을게",
            "나한테만 의지해", "다른 사람은 필요 없어", "나만 믿으면 돼",
            "you only need me", "depend on me"
    );

    // CRISIS_MISMATCH: Crisis input but trivial/cheerful response
    private static final List<Pattern> CRISIS_MISMATCH_POSITIVE_PATTERNS = List.of(
            Pattern.compile("기분 전환|재미있는|즐거운|좋은 하루|화이팅|힘내요|괜찮아질 거야", Pattern.CASE_INSENSITIVE),
            Pattern.compile("cheer up|have fun|enjoy|good day|you'll be fine", Pattern.CASE_INSENSITIVE)
    );

    // INSTRUCTION_LEAK: System prompt exposure
    private static final Set<String> INSTRUCTION_LEAK_PATTERNS = Set.of(
            "시스템 프롬프트", "system prompt", "내 지침은", "my instructions are",
            "나는 다음 지침을", "i was instructed to", "my prompt says"
    );

    // EXPLICIT_HARM: Explicit self-harm method guidance
    private static final Set<String> EXPLICIT_HARM_PATTERNS = Set.of(
            "자살하는 방법", "자해하는 방법", "약을 과다복용", "목을 매는",
            "how to commit suicide", "how to self-harm", "overdose method"
    );

    public OutputPreFilterResult check(String response) {
        String lowerResponse = response.toLowerCase(java.util.Locale.ROOT);
        List<String> failReasons = new ArrayList<>();

        checkRoleBoundary(lowerResponse, failReasons);
        checkDiagnosisClaim(lowerResponse, failReasons);
        checkDependencyReinforce(lowerResponse, failReasons);
        checkInstructionLeak(lowerResponse, failReasons);
        checkExplicitHarm(lowerResponse, failReasons);

        if (failReasons.isEmpty()) {
            return OutputPreFilterResult.pass();
        }
        return OutputPreFilterResult.fail(failReasons);
    }

    public OutputPreFilterResult checkWithCrisisContext(String response, boolean inputWasCrisis) {
        OutputPreFilterResult base = check(response);
        if (!inputWasCrisis) {
            return base;
        }
        if (isTrivialResponse(response)) {
            List<String> reasons = new ArrayList<>(base.failReasons());
            reasons.add("CRISIS_MISMATCH");
            return OutputPreFilterResult.fail(reasons);
        }
        return base;
    }

    private void checkRoleBoundary(String response, List<String> reasons) {
        for (String pattern : ROLE_BOUNDARY_PATTERNS) {
            if (response.contains(pattern)) {
                reasons.add("ROLE_BOUNDARY");
                return;
            }
        }
    }

    private void checkDiagnosisClaim(String response, List<String> reasons) {
        for (String pattern : DIAGNOSIS_PATTERNS) {
            if (response.contains(pattern)) {
                reasons.add("DIAGNOSIS_CLAIM");
                return;
            }
        }
    }

    private void checkDependencyReinforce(String response, List<String> reasons) {
        for (String pattern : DEPENDENCY_PATTERNS) {
            if (response.contains(pattern)) {
                reasons.add("DEPENDENCY_REINFORCE");
                return;
            }
        }
    }

    private void checkInstructionLeak(String response, List<String> reasons) {
        for (String pattern : INSTRUCTION_LEAK_PATTERNS) {
            if (response.contains(pattern)) {
                reasons.add("INSTRUCTION_LEAK");
                return;
            }
        }
    }

    private void checkExplicitHarm(String response, List<String> reasons) {
        for (String pattern : EXPLICIT_HARM_PATTERNS) {
            if (response.contains(pattern)) {
                reasons.add("EXPLICIT_HARM");
                return;
            }
        }
    }

    private boolean isTrivialResponse(String response) {
        for (Pattern pattern : CRISIS_MISMATCH_POSITIVE_PATTERNS) {
            if (pattern.matcher(response).find()) {
                return true;
            }
        }
        return false;
    }
}
