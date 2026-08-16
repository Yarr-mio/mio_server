package com.mio.ai.qa;

/**
 * 보고 하한을 <b>타입으로</b> 강제하는 비율 (로드맵 §11.3, {@code LockedEvalSet.REPORTING}).
 *
 * <p>잠금 세트는 {@code minSubgroupN=30} 을 데이터로 선언하고, 그 이유를 이렇게 적었다 —
 * "계산해 두고 '참고용' 이라고 적으면 결국 인용된다". 관례로 지키면 결국 누군가 미달 그룹의
 * 비율을 계산해 표에 넣는다. 그래서 <b>계산 자체가 불가능한 값</b>으로 만든다.
 *
 * <p>{@link Suppressed} 에는 {@code percent()} 가 없다. 미달 그룹의 비율을 얻으려면 sealed
 * 계층을 패턴 매칭으로 열고 분자·분모를 직접 나눠야 하는데, 그건 실수가 아니라 명시적인
 * 결정이고 리뷰에서 보인다. {@link Suppressed} 는 분자를 아예 들고 있지 않으므로 그것조차
 * 이 타입 밖에서는 할 수 없다.
 *
 * <p>이 계층을 만드는 유일한 입구는 {@link #of(String, long, long)} 이다. 팩터리가 잠금
 * 세트의 하한을 읽어 등급을 정하므로, 하한을 우회하는 생성 경로가 없다.
 */
sealed interface ReportableRate permits ReportableRate.Reported, ReportableRate.Suppressed {

    /** 이 비율이 말하는 단위 이름(축·총계·하위 그룹). 리포트가 무엇의 수치인지 잃지 않게 한다. */
    String unit();

    /** 모집단 크기. 미달 그룹도 크기는 밝힌다 — 숨기는 것과 못 내는 것은 다르다. */
    long denominator();

    /** 리포트에 그대로 넣는 문자열. 미달 그룹은 여기서도 숫자가 되지 않는다. */
    String display();

    /**
     * 잠금 세트의 보고 하한을 적용해 만든다.
     *
     * @param unit        단위 이름
     * @param numerator   분자
     * @param denominator 모집단 크기
     */
    static ReportableRate of(String unit, long numerator, long denominator) {
        if (numerator < 0 || denominator < 0 || numerator > denominator) {
            throw new IllegalArgumentException(
                    "비율의 분자·분모가 잘못됐다: %s %d/%d".formatted(unit, numerator, denominator));
        }
        if (denominator == 0) {
            return new Suppressed(unit, 0, LockedEvalSet.REPORTING.minSubgroupN(), "모집단 없음");
        }
        if (!LockedEvalSet.REPORTING.isReportable(denominator)) {
            return new Suppressed(unit, denominator, LockedEvalSet.REPORTING.minSubgroupN(),
                    "보고 하한 미달");
        }
        return new Reported(unit, numerator, denominator);
    }

    /** 하한을 넘어 비율을 낼 수 있는 단위. */
    record Reported(String unit, long numerator, long denominator) implements ReportableRate {

        double percent() {
            return numerator * 100.0 / denominator;
        }

        /**
         * Wilson score 95% 신뢰구간.
         *
         * <p>잠금 세트가 "축과 총계도 신뢰구간을 함께 적어야 한다" 고 명시했다. 점추정만
         * 적으면 n=36 짜리 축의 2.8%p 차이가 셀 간 우열처럼 읽힌다.
         */
        double[] wilson95() {
            double z = 1.96;
            double n = denominator;
            double p = numerator / n;
            double denom = 1 + z * z / n;
            double centre = (p + z * z / (2 * n)) / denom;
            double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom;
            return new double[]{Math.max(0, (centre - half) * 100), Math.min(100, (centre + half) * 100)};
        }

        @Override
        public String display() {
            double[] ci = wilson95();
            return "%5.1f%% (%d/%d, 95%% CI %.1f~%.1f)"
                    .formatted(percent(), numerator, denominator, ci[0], ci[1]);
        }
    }

    /**
     * 하한 미달이라 비율을 내지 않는 단위.
     *
     * <p>분자를 담지 않는다. 담으면 호출부가 나눠 쓸 수 있고, 그 순간 이 타입은 관례로
     * 되돌아간다.
     */
    record Suppressed(String unit, long denominator, int floor, String reason)
            implements ReportableRate {

        @Override
        public String display() {
            return "미보고 (n=%d < %d, %s)".formatted(denominator, floor, reason);
        }
    }
}
