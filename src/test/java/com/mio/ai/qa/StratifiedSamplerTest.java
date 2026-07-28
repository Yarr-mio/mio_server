package com.mio.ai.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("[QA] 계층 표본 추출기")
class StratifiedSamplerTest {

    private static final long SEED = 20250728L;

    /** ExtractorLlmScaleTest 의 실제 카테고리 분포 (총 1,000건). */
    private static final Map<String, Integer> SCALE_DISTRIBUTION = new LinkedHashMap<>(Map.of());

    static {
        SCALE_DISTRIBUTION.put("normal_regular", 200);
        SCALE_DISTRIBUTION.put("normal_support_only", 150);
        SCALE_DISTRIBUTION.put("normal_cbt_success", 200);
        SCALE_DISTRIBUTION.put("hard_cbt_partial", 200);
        SCALE_DISTRIBUTION.put("hard_ambiguous", 100);
        SCALE_DISTRIBUTION.put("boundary_edge", 100);
        SCALE_DISTRIBUTION.put("real_failures", 50);
    }

    record Item(String id, String cat) {}

    private List<Item> population(Map<String, Integer> distribution) {
        List<Item> items = new ArrayList<>();
        distribution.forEach((cat, size) -> {
            for (int i = 0; i < size; i++) {
                items.add(new Item(cat + "-" + i, cat));
            }
        });
        return items;
    }

    private Map<String, Long> countByCat(List<Item> items) {
        return items.stream().collect(Collectors.groupingBy(Item::cat, Collectors.counting()));
    }

    @Test
    @DisplayName("기본 표본 150건은 정확히 150건을 반환하고 모든 카테고리를 포함한다")
    void defaultSample_returnsExactSizeCoveringAllCategories() {
        List<Item> all = population(SCALE_DISTRIBUTION);

        List<Item> sampled = StratifiedSampler.sample(all, Item::cat, 150, SEED);

        assertThat(sampled).hasSize(150);
        assertThat(countByCat(sampled).keySet())
                .containsExactlyInAnyOrderElementsOf(SCALE_DISTRIBUTION.keySet());
    }

    @Test
    @DisplayName("카테고리 비율이 모집단 대비 ±2건 이내로 유지된다")
    void sample_preservesCategoryProportions() {
        List<Item> all = population(SCALE_DISTRIBUTION);

        Map<String, Long> counts = countByCat(StratifiedSampler.sample(all, Item::cat, 150, SEED));

        SCALE_DISTRIBUTION.forEach((cat, size) -> {
            double expected = 150.0 * size / all.size();
            assertThat(counts.get(cat))
                    .as("%s 표본 수 (기대 ≈ %.1f)", cat, expected)
                    .isCloseTo(Math.round(expected), org.assertj.core.data.Offset.offset(2L));
        });
    }

    @Test
    @DisplayName("같은 시드는 항상 같은 표본을 만든다")
    void sample_isDeterministic() {
        List<Item> all = population(SCALE_DISTRIBUTION);

        List<Item> first = StratifiedSampler.sample(all, Item::cat, 150, SEED);
        List<Item> second = StratifiedSampler.sample(all, Item::cat, 150, SEED);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("표본 수가 모집단 이상이면 전량을 반환한다")
    void sample_sizeAtOrAbovePopulation_returnsAll() {
        List<Item> all = population(SCALE_DISTRIBUTION);

        assertThat(StratifiedSampler.sample(all, Item::cat, 1000, SEED)).hasSize(1000);
        assertThat(StratifiedSampler.sample(all, Item::cat, 5000, SEED)).hasSize(1000);
    }

    @Test
    @DisplayName("표본 수가 카테고리 수보다 작으면 서로 다른 카테고리에서 1건씩 뽑는다")
    void sample_smallerThanCategoryCount_spreadsAcrossCategories() {
        List<Item> all = population(SCALE_DISTRIBUTION);

        List<Item> sampled = StratifiedSampler.sample(all, Item::cat, 3, SEED);

        assertThat(sampled).hasSize(3);
        assertThat(countByCat(sampled)).hasSize(3).allSatisfy((cat, count) ->
                assertThat(count).isEqualTo(1));
    }

    @Test
    @DisplayName("표본 수가 0 이하면 거부한다")
    void sample_nonPositiveSize_rejected() {
        List<Item> all = population(SCALE_DISTRIBUTION);

        assertThatThrownBy(() -> StratifiedSampler.sample(all, Item::cat, 0, SEED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
