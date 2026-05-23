package com.mio.onboarding.service;

import com.mio.onboarding.dto.CharacterRecommendationDto;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class CharacterRecommender {

    private static final List<CharacterProfile> PROFILES = List.of(
        new CharacterProfile("mio", "미오",
            Map.of("empathetic", 1.0, "balanced", 0.6, "analytical", 0.3, "solution", 0.3),
            Map.of("anxious", 1.0, "sad", 1.0, "happy", 0.5, "calm", 0.6,
                   "angry", 0.3, "ashamed", 0.6, "numb", 0.5, "tired", 0.6, "confused", 0.5),
            Map.of("relationship", 1.0, "family", 1.0, "romance", 1.0,
                   "career", 0.5, "workload", 0.5, "financial", 0.4,
                   "lifestyle", 0.6, "health", 0.5, "other", 0.5),
            List.of(
                "불안하고 복잡한 감정 상태에서 공감형 접근이 효과적이에요.",
                "관계와 감정 문제에 따뜻하게 함께해드려요.",
                "감정을 있는 그대로 인정하며 차근차근 풀어가요."
            )
        ),
        new CharacterProfile("bau", "바우",
            Map.of("empathetic", 0.6, "balanced", 1.0, "analytical", 0.6, "solution", 0.7),
            Map.of("anxious", 0.7, "sad", 0.7, "happy", 0.7, "calm", 0.7,
                   "angry", 0.7, "ashamed", 0.7, "numb", 0.7, "tired", 0.7, "confused", 0.7),
            Map.of("relationship", 0.7, "family", 0.7, "romance", 0.7,
                   "career", 0.7, "workload", 0.7, "financial", 0.7,
                   "lifestyle", 0.7, "health", 0.7, "other", 0.7),
            List.of(
                "어떤 상황에도 균형 잡힌 시각으로 함께해요.",
                "다양한 고민을 폭넓게 다루는 든든한 파트너예요.",
                "상황에 맞게 유연하게 접근 방식을 조율해드려요."
            )
        ),
        new CharacterProfile("rumi", "루미",
            Map.of("empathetic", 0.4, "balanced", 0.6, "analytical", 1.0, "solution", 0.7),
            Map.of("anxious", 0.7, "sad", 0.5, "happy", 0.6, "calm", 0.8,
                   "angry", 0.5, "ashamed", 0.4, "numb", 0.5, "tired", 0.5, "confused", 1.0),
            Map.of("relationship", 0.5, "family", 0.5, "romance", 0.4,
                   "career", 1.0, "workload", 0.8, "financial", 1.0,
                   "lifestyle", 0.6, "health", 0.6, "other", 0.5),
            List.of(
                "인지 재구성으로 불안한 사고 패턴을 정리해드려요.",
                "논리적으로 고민을 분석하고 명확한 방향을 제시해요.",
                "커리어와 재정 문제를 체계적으로 접근해요."
            )
        ),
        new CharacterProfile("momo", "모모",
            Map.of("empathetic", 0.9, "balanced", 0.9, "analytical", 0.4, "solution", 0.5),
            Map.of("anxious", 0.7, "sad", 0.8, "happy", 0.5, "calm", 0.5,
                   "angry", 0.5, "ashamed", 1.0, "numb", 1.0, "tired", 1.0, "confused", 0.6),
            Map.of("relationship", 0.7, "family", 0.7, "romance", 0.7,
                   "career", 0.6, "workload", 0.7, "financial", 0.5,
                   "lifestyle", 0.7, "health", 0.7, "other", 0.6),
            List.of(
                "자책과 감정 과부하에 수용전념치료(ACT) 방식으로 접근해요.",
                "지치고 무기력한 감정을 따뜻하게 감싸드려요.",
                "스스로를 너무 몰아붙이는 당신 곁에 있을게요."
            )
        ),
        new CharacterProfile("chichi", "치치",
            Map.of("empathetic", 0.4, "balanced", 0.5, "analytical", 0.7, "solution", 1.0),
            Map.of("anxious", 0.6, "sad", 0.4, "happy", 0.7, "calm", 0.6,
                   "angry", 1.0, "ashamed", 0.4, "numb", 0.4, "tired", 0.6, "confused", 0.5),
            Map.of("relationship", 0.5, "family", 0.5, "romance", 0.4,
                   "career", 1.0, "workload", 1.0, "financial", 0.7,
                   "lifestyle", 0.6, "health", 0.6, "other", 0.5),
            List.of(
                "명확한 목표와 실행 계획으로 변화를 이끌어요.",
                "업무 스트레스와 번아웃을 실용적으로 해결해드려요.",
                "분노와 답답함을 건강한 행동 변화로 전환해요."
            )
        )
    );

    public List<CharacterRecommendationDto> recommend(
            String emotionState, List<String> concernTypes, String preferredStyle) {
        return PROFILES.stream()
                .map(p -> score(p, emotionState, concernTypes, preferredStyle))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(3)
                .map(s -> new CharacterRecommendationDto(
                        s.profile().characterId(),
                        s.profile().name(),
                        Math.round(s.score() * 100.0) / 100.0,
                        selectReason(s.profile(), s.score())
                ))
                .toList();
    }

    private Scored score(CharacterProfile p, String emotion, List<String> concerns, String style) {
        double styleScore = p.styleScores().getOrDefault(style, 0.3);
        double emotionScore = p.emotionScores().getOrDefault(emotion, 0.5);
        double concernScore = concerns.stream()
                .mapToDouble(c -> p.concernScores().getOrDefault(c, 0.5))
                .max()
                .orElse(0.5);
        double total = styleScore * 0.4 + emotionScore * 0.35 + concernScore * 0.25;
        return new Scored(p, total);
    }

    private String selectReason(CharacterProfile profile, double score) {
        List<String> reasons = profile.reasons();
        if (score >= 0.85) return reasons.get(0);
        if (score >= 0.70) return reasons.get(1);
        return reasons.get(2);
    }

    private record CharacterProfile(
            String characterId,
            String name,
            Map<String, Double> styleScores,
            Map<String, Double> emotionScores,
            Map<String, Double> concernScores,
            List<String> reasons
    ) {}

    private record Scored(CharacterProfile profile, double score) {}
}
