package com.mio.ai.qa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 카테고리 비율을 유지한 채 표본을 뽑는 QA 테스트용 헬퍼.
 *
 * <p>실 LLM 호출 비용 때문에 전량 실행이 어려운 시나리오 코퍼스에서, 카테고리 분포를 왜곡하지 않고
 * 일부만 실행하기 위해 사용한다. 시드가 고정되어 있어 실행마다 동일한 표본이 선택되므로 회귀 비교가
 * 가능하다.
 */
final class StratifiedSampler {

    private StratifiedSampler() {
    }

    /**
     * @param all        전체 모집단 (입력 순서가 카테고리 순서를 결정한다)
     * @param categoryOf 원소에서 카테고리 키를 뽑는 함수
     * @param sampleSize 뽑을 표본 수. 모집단 크기 이상이면 전량을 그대로 반환한다.
     * @param seed       셔플 시드
     * @return 카테고리별 비율을 반영한 표본. 각 카테고리는 표본 수가 허용하는 한 최소 1건을 포함한다.
     */
    static <T> List<T> sample(List<T> all, Function<T, String> categoryOf, int sampleSize, long seed) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize 는 1 이상이어야 한다: " + sampleSize);
        }
        if (sampleSize >= all.size()) {
            return List.copyOf(all);
        }

        Map<String, List<T>> byCat = all.stream()
                .collect(Collectors.groupingBy(categoryOf, LinkedHashMap::new, Collectors.toList()));

        List<T> sampled = new ArrayList<>(sampleSize);
        int remaining = sampleSize;
        int catsLeft = byCat.size();

        for (Map.Entry<String, List<T>> entry : byCat.entrySet()) {
            if (remaining == 0) {
                break;
            }
            List<T> pool = new ArrayList<>(entry.getValue());
            Collections.shuffle(pool, new Random(seed + entry.getKey().hashCode()));

            int quota;
            if (catsLeft == 1) {
                // 마지막 카테고리가 반올림으로 남은 잔여분을 흡수한다.
                quota = Math.min(remaining, pool.size());
            } else if (remaining < catsLeft) {
                // 표본 수가 카테고리 수보다 적은 경우 — 앞쪽 카테고리부터 1건씩 채운다.
                quota = 1;
            } else {
                int proportional = Math.max(1, Math.round(sampleSize * (float) pool.size() / all.size()));
                // 뒤에 남은 카테고리에 최소 1건씩 남겨 둔다.
                quota = Math.min(proportional, Math.min(pool.size(), remaining - (catsLeft - 1)));
            }

            sampled.addAll(pool.subList(0, quota));
            remaining -= quota;
            catsLeft--;
        }
        return sampled;
    }
}
