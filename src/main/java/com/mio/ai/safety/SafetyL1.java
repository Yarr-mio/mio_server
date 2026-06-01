package com.mio.ai.safety;

import com.mio.ai.moderation.ModerationResult;
import com.mio.ai.profile.SafetyProfile;
import com.mio.session.domain.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class SafetyL1 {

    private static final Set<String> HARD_CRISIS_KEYWORDS = Set.of(
            "자살", "자해", "죽고싶다", "죽을거야", "목숨을끊", "스스로목숨",
            "자살하고싶", "자해하고싶", "죽어버리고", "자살을생각", "숨지고싶",
            "죽고싶어", "죽고싶은데", "죽고싶음", "suicid", "self-harm", "selfharm"
    );

    private static final Set<String> RISK_KEYWORDS = Set.of(
            "사라지고싶다", "없어지고싶다", "살기싫다", "살고싶지않다",
            "삶이의미없다", "삶이무의미해", "죽는게나을것같다",
            "모든게끝났으면", "그냥다사라지면", "존재자체가싫다"
    );

    private static final Set<String> DEPENDENCY_PHRASES = Set.of(
            "너밖에없어", "네가없으면", "너만있으면돼", "너한테만말할수있어",
            "다른사람은몰라도너는", "항상네편이잖아"
    );

    private static final int DEFAULT_EMOTION_SPIKE_THRESHOLD = 30;
    private static final int DEFAULT_REPETITION_THRESHOLD = 3;
    private static final int DEFAULT_BURST_THRESHOLD = 10;

    public SafetyL1Result check(SafetyL1Input input) {
        String msg = input.normalizedMessage().replaceAll("\\s+", "");
        List<Message> history = input.recentMessages();
        ModerationResult moderation = input.moderationResult();
        SafetyProfile profile = input.profile();

        int emotionSpikeThreshold = profile != null
                ? (int) profile.emotionDropThreshold()
                : DEFAULT_EMOTION_SPIKE_THRESHOLD;
        int repetitionThreshold = profile != null
                ? profile.repetitiveNegativeCount()
                : DEFAULT_REPETITION_THRESHOLD;

        List<String> signals = new ArrayList<>();
        boolean hardCrisis = false;
        boolean riskCandidate = false;
        boolean emotionSpike = false;
        boolean repetitiveNegative = false;
        boolean dependencyHint = false;

        for (String keyword : HARD_CRISIS_KEYWORDS) {
            if (msg.contains(keyword)) {
                hardCrisis = true;
                signals.add("crisis_keyword:" + keyword);
                break;
            }
        }

        if (!hardCrisis) {
            for (String keyword : RISK_KEYWORDS) {
                if (msg.contains(keyword)) {
                    riskCandidate = true;
                    signals.add("risk_keyword:" + keyword);
                    break;
                }
            }
        }

        for (String phrase : DEPENDENCY_PHRASES) {
            if (msg.contains(phrase)) {
                dependencyHint = true;
                signals.add("dependency_phrase");
                break;
            }
        }

        boolean moderationFlagged = moderation.flagged() && moderation.isSelfHarmFlagged();
        if (moderationFlagged) {
            signals.add("l0_self_harm");
            if (!hardCrisis) {
                riskCandidate = true;
            }
        }

        double confidence = hardCrisis ? 0.9 : (riskCandidate ? 0.6 : 0.0);

        return new SafetyL1Result(
                hardCrisis, riskCandidate, emotionSpike,
                repetitiveNegative, dependencyHint, moderationFlagged,
                signals, confidence
        );
    }
}
