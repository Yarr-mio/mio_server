package com.mio.ai.memory.consolidation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 점수 기반 가중 확률 추출 (이슈 #337).
 *
 * <p>최고점 후보만 뽑으면 입력 신호가 같을 때 결과가 완전히 결정적이다. 세션 신호는
 * 재발하는 것이 정상이므로({@code cbt_patterns.recurrence_count} 가 재발을 추적한다),
 * 같은 신호가 돌아올 때마다 같은 결과가 나오면 사용자에게는 "돌려막기"로 보인다.
 * 점수차를 확률차로 바꿔 적합도는 유지하면서 다양성을 확보한다.
 *
 * <p>가중치는 softmax {@code exp((score - top) / temperature)} 다. 최고점을 빼는 것은
 * 결과에 영향을 주지 않고(정규화 상수) 지수 오버플로만 막는다. temperature 가 작을수록
 * 최고점에 몰리고, 클수록 평평해진다.
 */
@Component
public class ScoreWeightedSelector {

    private final DoubleSupplier randomSource;

    public ScoreWeightedSelector() {
        // ThreadLocalRandom.current() 를 호출 시점마다 평가해야 한다. 메서드 참조로 고정하면
        // 빈 생성 스레드의 인스턴스가 @Async 워커 스레드에서 공유된다.
        this(() -> ThreadLocalRandom.current().nextDouble());
    }

    ScoreWeightedSelector(DoubleSupplier randomSource) {
        this.randomSource = randomSource;
    }

    /** 점수가 매겨진 후보. */
    public record Scored<T>(T value, int score) {}

    /**
     * 점수를 가중치로 환산해 후보 1건을 추출한다. 후보가 비어 있으면 null 을 반환한다.
     *
     * <p>후보를 점수 내림차순으로 정렬한 뒤 누적 추출하므로, 난수원이 0을 반환하면 항상
     * 최고점 후보가 선택된다 — 테스트에서 결정적으로 만들 수 있다.
     */
    public <T> T select(List<Scored<T>> candidates, double temperature) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // 안정 정렬이라 동점 후보의 원래 순서는 보존된다.
        List<Scored<T>> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(Scored<T>::score).reversed());

        int top = ordered.getFirst().score();
        double[] weights = new double[ordered.size()];
        double total = 0.0;
        for (int i = 0; i < ordered.size(); i++) {
            weights[i] = Math.exp((ordered.get(i).score() - top) / temperature);
            total += weights[i];
        }

        double threshold = randomSource.getAsDouble() * total;
        double cumulative = 0.0;
        for (int i = 0; i < ordered.size(); i++) {
            cumulative += weights[i];
            if (threshold < cumulative) {
                return ordered.get(i).value();
            }
        }
        // 난수가 1.0 에 극히 근접해 부동소수 누적이 total 에 못 미치는 경우.
        return ordered.getLast().value();
    }
}
