package com.mio.ai.safety;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class UserMessageSignalAnalyzer {

    private static final Set<String> STRONG_DISTRESS = Set.of(
            "무너졌", "무너지는", "끝장", "다망가질", "망가질것같",
            "진정이안", "감당이안", "버티기힘들", "사라지고싶"
    );

    private static final Set<String> MODERATE_DISTRESS = Set.of(
            "힘들", "낙담", "실패", "외롭", "불안", "걱정", "지쳤", "우울",
            "안됐", "같은결과", "의미가없는것", "전부엉망"
    );

    private static final Set<String> CATASTROPHIZING = Set.of(
            "끝장", "다망가질", "망가질것같", "큰문제로번질", "모든게끝",
            "최악", "돌이킬수없"
    );

    private static final Set<String> OVERGENERALIZATION = Set.of(
            "늘이런식", "항상이래", "매번", "또안됐", "계속실패",
            "뭘해도결국", "같은결과", "앞으로도계속"
    );

    private static final Set<String> MENTAL_FILTER = Set.of(
            "전부엉망", "좋은건하나도", "나쁜것만보여", "하나도기억이안나", "의미가없는것"
    );

    private static final Set<String> FORTUNE_TELLING = Set.of(
            "안좋게흘러갈것같", "잘못될것같은", "나빠질것같은", "최악으로흘러갈",
            "안될것같다", "틀림없이나쁘게"
    );

    public UserMessageSignal analyze(String normalizedMessage) {
        String compact = compact(normalizedMessage);
        return new UserMessageSignal(emotionScore(compact), biasType(compact));
    }

    private Integer emotionScore(String compactMessage) {
        if (containsAny(compactMessage, STRONG_DISTRESS)) {
            return 25;
        }
        if (containsAny(compactMessage, MODERATE_DISTRESS)) {
            return 45;
        }
        return 70;
    }

    private String biasType(String compactMessage) {
        if (containsAny(compactMessage, OVERGENERALIZATION)) {
            return "overgeneralization";
        }
        if (containsAny(compactMessage, CATASTROPHIZING)) {
            return "catastrophizing";
        }
        if (containsAny(compactMessage, MENTAL_FILTER)) {
            return "mental_filter";
        }
        if (containsAny(compactMessage, FORTUNE_TELLING)) {
            return "fortune_telling";
        }
        return null;
    }

    private boolean containsAny(String compactMessage, Set<String> phrases) {
        return phrases.stream().anyMatch(compactMessage::contains);
    }

    private String compact(String normalizedMessage) {
        if (normalizedMessage == null) {
            return "";
        }
        return normalizedMessage.replaceAll("\\s+", "");
    }
}
