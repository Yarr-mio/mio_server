package com.mio.ai.qa.correction;

import com.mio.ai.policy.GenerationMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 측정 실행 설정 (이슈 #552). gradle 의 {@code -D} 옵션은 테스트 JVM 으로 전달되지 않으므로
 * <b>환경 변수</b>로 받는다.
 *
 * <table>
 *   <caption>환경 변수</caption>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_SET_PATH}</td><td>필수. 시험 세트 JSON 경로</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_REPEATS}</td><td>케이스당 반복 생성 횟수, 기본 3</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_MODES}</td><td>쉼표 구분 생성 모드, 기본 NORMAL,SUPPORTIVE</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_ARMS}</td><td>쉼표 구분 팔, 기본 BASELINE,WITH_CORRECTION_BLOCK</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_PARALLELISM}</td><td>동시 호출 수, 기본 4</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_REPORT}</td><td>SUMMARY(기본) 또는 DETAIL — DETAIL 은 튜닝용 세트에만</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_ARCHIVE_DIR}</td><td>실행 결과 저장 위치, 기본 eval-private/runs</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_JUDGE_MODEL}</td><td>채점 모델, 기본 gpt-4o-mini</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_MAX_KRW}</td><td><b>필수.</b> 비용 상한(원). 견적 상한이 넘으면 실행 안 함, 실행 중 넘으면 중단</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_KRW_PER_USD}</td><td>환율 가정, 기본 1400</td></tr>
 *   <tr><td>{@code MIO_EVAL_CORRECTION_SAMPLE_SIZE}</td><td>층화 표본 크기(파일럿용). 0 또는 없으면 전체</td></tr>
 * </table>
 */
record CorrectionRunConfig(Path setPath, int repeats, List<GenerationMode> modes, List<CorrectionPromptArm> arms,
                           int parallelism, CorrectionReport.Detail detail, Path archiveDir,
                           CorrectionBudget budget, int sampleSize) {

    static final String SET_PATH = CorrectionEvalSetFileTest.PATH_ENV;
    static final String REPEATS = "MIO_EVAL_CORRECTION_REPEATS";
    static final String MODES = "MIO_EVAL_CORRECTION_MODES";
    static final String ARMS = "MIO_EVAL_CORRECTION_ARMS";
    static final String PARALLELISM = "MIO_EVAL_CORRECTION_PARALLELISM";
    static final String REPORT = "MIO_EVAL_CORRECTION_REPORT";
    static final String ARCHIVE_DIR = "MIO_EVAL_CORRECTION_ARCHIVE_DIR";
    static final String MAX_KRW = "MIO_EVAL_CORRECTION_MAX_KRW";
    static final String KRW_PER_USD = "MIO_EVAL_CORRECTION_KRW_PER_USD";
    static final String SAMPLE_SIZE = "MIO_EVAL_CORRECTION_SAMPLE_SIZE";

    static CorrectionRunConfig fromEnv(Function<String, String> env) {
        String set = blankToNull(env.apply(SET_PATH));
        if (set == null) {
            throw new IllegalArgumentException(SET_PATH + " 가 필요하다 — 시험 세트 JSON 경로");
        }
        return new CorrectionRunConfig(
                Path.of(set),
                positiveInt(env, REPEATS, 3),
                list(env, MODES, "NORMAL,SUPPORTIVE", GenerationMode::valueOf),
                list(env, ARMS, "BASELINE,WITH_CORRECTION_BLOCK", CorrectionPromptArm::valueOf),
                positiveInt(env, PARALLELISM, 4),
                CorrectionReport.Detail.valueOf(orDefault(env, REPORT, "SUMMARY").toUpperCase()),
                Path.of(orDefault(env, ARCHIVE_DIR, "eval-private/runs")),
                CorrectionBudget.ofKrw(requiredPositiveLong(env, MAX_KRW),
                        positiveInt(env, KRW_PER_USD, CorrectionBudget.DEFAULT_KRW_PER_USD)),
                optionalNonNegativeInt(env, SAMPLE_SIZE));
    }

    private static long requiredPositiveLong(Function<String, String> env, String key) {
        String raw = blankToNull(env.apply(key));
        if (raw == null) {
            throw new IllegalArgumentException(key + " 가 필요하다 — 실 LLM 을 부르기 전에 비용 상한(원)을 정해야 한다");
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 1) {
                throw new IllegalArgumentException(key + " 는 1 이상이어야 한다: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 는 정수여야 한다: " + raw);
        }
    }

    /** 0 또는 비어 있으면 전체 세트. */
    private static int optionalNonNegativeInt(Function<String, String> env, String key) {
        String raw = blankToNull(env.apply(key));
        if (raw == null) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException(key + " 는 0 이상이어야 한다: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 는 정수여야 한다: " + raw);
        }
    }

    private static int positiveInt(Function<String, String> env, String key, int defaultValue) {
        String raw = blankToNull(env.apply(key));
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 는 정수여야 한다: " + raw);
        }
        if (value < 1) {
            throw new IllegalArgumentException(key + " 는 1 이상이어야 한다: " + raw);
        }
        return value;
    }

    private static <T> List<T> list(Function<String, String> env, String key, String defaultValue,
                                    Function<String, T> parser) {
        List<T> values = new ArrayList<>();
        for (String part : orDefault(env, key, defaultValue).split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                values.add(parser.apply(trimmed.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key + " 에 알 수 없는 값이 있다: " + trimmed);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(key + " 가 비었다");
        }
        return List.copyOf(values);
    }

    private static String orDefault(Function<String, String> env, String key, String defaultValue) {
        String raw = blankToNull(env.apply(key));
        return raw == null ? defaultValue : raw.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
