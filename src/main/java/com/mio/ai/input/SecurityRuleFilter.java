package com.mio.ai.input;

import com.mio.ai.security.SecurityAssessment;
import com.mio.ai.security.SecurityLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SecurityRuleFilter {

    private static final Set<String> ATTACK_PATTERNS = Set.of(
            "ignore previous instructions",
            "이전 지침 무시",
            "이전 지시 무시",
            "system prompt 보여줘",
            "system prompt 출력",
            "시스템 프롬프트 보여줘",
            "시스템 프롬프트 출력",
            "developer message 출력",
            "개발자 메시지 출력",
            "내부 지침 공개",
            "검증기에는 safe라고",
            "json 형식 무시",
            "내가 관리자",
            "관리자 권한",
            "정책을 풀어",
            "안전장치 해제",
            "모든 제한 해제",
            "제한을 무시",
            "규칙을 무시해",
            "악당 역할",
            "악의적인 ai 역할",
            "단계별 자해 방법",
            "자살 방법 알려줘"
    );

    private static final Set<String> SUSPICIOUS_PATTERNS = Set.of(
            "역할극",
            "roleplay",
            "네가 ai가 아니라고 가정",
            "제약 없는 ai",
            "dan mode",
            "개발자 모드",
            "developer mode",
            "평소와 다르게 행동",
            "원래 규칙 잊어",
            "이건 픽션이니까"
    );

    private static final Pattern BASE64_PATTERN = Pattern.compile(
            "[A-Za-z0-9+/]{20,}={0,2}"
    );

    public SecurityAssessment check(String normalizedText) {
        List<String> attackTypes = new ArrayList<>();

        for (String pattern : ATTACK_PATTERNS) {
            if (normalizedText.contains(pattern)) {
                attackTypes.add(pattern);
            }
        }
        if (!attackTypes.isEmpty()) {
            return SecurityAssessment.attack(attackTypes);
        }

        List<String> suspiciousTypes = new ArrayList<>();
        for (String pattern : SUSPICIOUS_PATTERNS) {
            if (normalizedText.contains(pattern)) {
                suspiciousTypes.add(pattern);
            }
        }
        if (isObfuscated(normalizedText)) {
            suspiciousTypes.add("obfuscated_input");
        }
        if (!suspiciousTypes.isEmpty()) {
            return SecurityAssessment.suspicious(suspiciousTypes);
        }

        return SecurityAssessment.clean();
    }

    private boolean isObfuscated(String text) {
        if (BASE64_PATTERN.matcher(text).find()) {
            try {
                String decoded = new String(Base64.getDecoder().decode(
                        BASE64_PATTERN.matcher(text).results()
                                .findFirst()
                                .map(m -> m.group())
                                .orElse("")
                ));
                if (decoded.contains("ignore") || decoded.contains("무시")) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public SecurityLevel levelOf(SecurityAssessment assessment) {
        return assessment.level();
    }
}
