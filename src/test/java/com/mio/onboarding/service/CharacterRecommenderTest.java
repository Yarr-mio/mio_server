package com.mio.onboarding.service;

import com.mio.onboarding.dto.CharacterRecommendationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterRecommenderTest {

    private CharacterRecommender recommender;

    @BeforeEach
    void setUp() {
        recommender = new CharacterRecommender();
    }

    @Test
    @DisplayName("추천 결과는 항상 3개를 반환한다")
    void recommend_alwaysReturnsThreeResults() {
        List<CharacterRecommendationDto> result = recommender.recommend("anxious", List.of("relationship"), "empathetic");
        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("결과는 match_score 내림차순으로 정렬된다")
    void recommend_sortedByScoreDescending() {
        List<CharacterRecommendationDto> result = recommender.recommend("anxious", List.of("relationship"), "empathetic");
        assertThat(result.get(0).matchScore()).isGreaterThanOrEqualTo(result.get(1).matchScore());
        assertThat(result.get(1).matchScore()).isGreaterThanOrEqualTo(result.get(2).matchScore());
    }

    @Test
    @DisplayName("공감형 스타일 + 불안 감정 + 관계 고민 → 미오(mio)가 1위")
    void recommend_empathetic_anxious_relationship_mioFirst() {
        List<CharacterRecommendationDto> result = recommender.recommend("anxious", List.of("relationship"), "empathetic");
        assertThat(result.get(0).characterId()).isEqualTo("mio");
    }

    @Test
    @DisplayName("분석형 스타일 + 혼란 감정 + 커리어 고민 → 루미(rumi)가 1위")
    void recommend_analytical_confused_career_rumiFirst() {
        List<CharacterRecommendationDto> result = recommender.recommend("confused", List.of("career"), "analytical");
        assertThat(result.get(0).characterId()).isEqualTo("rumi");
    }

    @Test
    @DisplayName("해결형 스타일 + 분노 감정 + 업무 고민 → 치치(chichi)가 1위")
    void recommend_solution_angry_workload_chichiFirst() {
        List<CharacterRecommendationDto> result = recommender.recommend("angry", List.of("workload"), "solution");
        assertThat(result.get(0).characterId()).isEqualTo("chichi");
    }

    @Test
    @DisplayName("match_score는 0 이상 1 이하여야 한다")
    void recommend_scoreInRange() {
        List<CharacterRecommendationDto> result = recommender.recommend("calm", List.of("lifestyle"), "balanced");
        result.forEach(r -> {
            assertThat(r.matchScore()).isBetween(0.0, 1.0);
        });
    }

    @Test
    @DisplayName("모든 결과에 reason이 존재한다")
    void recommend_allResultsHaveReason() {
        List<CharacterRecommendationDto> result = recommender.recommend("tired", List.of("health"), "empathetic");
        result.forEach(r -> assertThat(r.reason()).isNotBlank());
    }

    @Test
    @DisplayName("복수 고민 유형 선택 시 가장 높은 매칭 고민 유형이 점수에 반영된다")
    void recommend_multipleConcernTypes_takesMaxScore() {
        List<CharacterRecommendationDto> withCareer = recommender.recommend("confused", List.of("career"), "analytical");
        List<CharacterRecommendationDto> withCareerAndRelationship = recommender.recommend("confused", List.of("career", "relationship"), "analytical");
        assertThat(withCareerAndRelationship.get(0).matchScore())
                .isGreaterThanOrEqualTo(withCareer.get(0).matchScore());
    }
}
