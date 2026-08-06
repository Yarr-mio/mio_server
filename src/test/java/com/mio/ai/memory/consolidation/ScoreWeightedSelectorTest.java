package com.mio.ai.memory.consolidation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreWeightedSelectorTest {

    private static final double T = 2.0;

    @Test
    @DisplayName("난수원이 0이면 항상 최고점 후보가 선택된다")
    void picksTopScoreWhenRandomIsZero() {
        var selector = new ScoreWeightedSelector(() -> 0.0);

        String picked = selector.select(List.of(
                new ScoreWeightedSelector.Scored<>("low", 0),
                new ScoreWeightedSelector.Scored<>("high", 4),
                new ScoreWeightedSelector.Scored<>("mid", 2)), T);

        assertThat(picked).isEqualTo("high");
    }

    @Test
    @DisplayName("동점이면 입력 순서가 앞선 후보가 선택된다")
    void keepsInputOrderAmongTies() {
        var selector = new ScoreWeightedSelector(() -> 0.0);

        String picked = selector.select(List.of(
                new ScoreWeightedSelector.Scored<>("first", 3),
                new ScoreWeightedSelector.Scored<>("second", 3)), T);

        assertThat(picked).isEqualTo("first");
    }

    @Test
    @DisplayName("후보가 비면 null을 반환한다")
    void returnsNullWhenEmpty() {
        var selector = new ScoreWeightedSelector(() -> 0.0);

        assertThat(selector.<String>select(List.of(), T)).isNull();
        assertThat(selector.<String>select(null, T)).isNull();
    }

    @Test
    @DisplayName("점수가 낮은 후보도 배제되지 않고 확률적으로 선택된다")
    void lowScoreCandidateIsStillSelectable() {
        var selector = new ScoreWeightedSelector(new Random(1234)::nextDouble);
        var candidates = List.of(
                new ScoreWeightedSelector.Scored<>("high", 4),
                new ScoreWeightedSelector.Scored<>("low", 0));

        Map<String, Integer> counts = draw(selector, candidates, 5000);

        assertThat(counts.get("low")).isPositive();
        // exp(-4/2) = 0.135 → 약 12%. 배제(0%)도 균등(50%)도 아님을 확인한다.
        assertThat(counts.get("low") / 5000.0).isBetween(0.07, 0.19);
        assertThat(counts.get("high")).isGreaterThan(counts.get("low"));
    }

    @Test
    @DisplayName("점수 순서가 선택 빈도 순서로 유지된다")
    void higherScoreIsChosenMoreOften() {
        var selector = new ScoreWeightedSelector(new Random(99)::nextDouble);
        var candidates = List.of(
                new ScoreWeightedSelector.Scored<>("a", 0),
                new ScoreWeightedSelector.Scored<>("b", 2),
                new ScoreWeightedSelector.Scored<>("c", 4));

        Map<String, Integer> counts = draw(selector, candidates, 5000);

        assertThat(counts.get("c")).isGreaterThan(counts.get("b"));
        assertThat(counts.get("b")).isGreaterThan(counts.get("a"));
    }

    @Test
    @DisplayName("온도가 낮을수록 최고점에 더 몰린다")
    void lowerTemperatureConcentratesOnTopScore() {
        var candidates = List.of(
                new ScoreWeightedSelector.Scored<>("high", 4),
                new ScoreWeightedSelector.Scored<>("low", 0));

        int sharp = draw(new ScoreWeightedSelector(new Random(7)::nextDouble), candidates, 5000)
                .getOrDefault("high", 0);
        int flat = draw(new ScoreWeightedSelector(new Random(7)::nextDouble), candidates, 5000, 8.0)
                .getOrDefault("high", 0);

        assertThat(sharp).isGreaterThan(flat);
    }

    private Map<String, Integer> draw(ScoreWeightedSelector selector,
                                      List<ScoreWeightedSelector.Scored<String>> candidates, int times) {
        return draw(selector, candidates, times, T);
    }

    private Map<String, Integer> draw(ScoreWeightedSelector selector,
                                      List<ScoreWeightedSelector.Scored<String>> candidates,
                                      int times, double temperature) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < times; i++) {
            counts.merge(selector.select(candidates, temperature), 1, Integer::sum);
        }
        return counts;
    }
}
