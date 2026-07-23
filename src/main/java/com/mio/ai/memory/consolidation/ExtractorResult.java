package com.mio.ai.memory.consolidation;

import java.util.List;

/**
 * ExtractorLLM(GPT-4o-mini)이 세션 내용에서 추출한 결과.
 */
public record ExtractorResult(
        List<ExtractedThought> thoughts,
        String dominantEmotion,
        List<String> triggerTags,
        String episodeType,
        Integer emotionScore
) {
    public record ExtractedThought(
            String thoughtText,
            String distortionCode,
            String beliefKind,
            String polarity,
            double confidence,
            String beliefIdentity,
            String evidenceKind
    ) {
        public ExtractedThought(String thoughtText, String distortionCode, String beliefKind,
                                String polarity, double confidence) {
            this(thoughtText, distortionCode, beliefKind, polarity, confidence, null, null);
        }
    }

    public static ExtractorResult empty() {
        return new ExtractorResult(List.of(), null, List.of(), "regular", null);
    }
}
