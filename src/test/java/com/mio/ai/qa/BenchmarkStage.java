package com.mio.ai.qa;

import java.util.List;
import java.util.Locale;

/**
 * 모델 선정 깔때기의 단계 (이슈 #454).
 *
 * <h2>왜 단계를 값으로 두는가</h2>
 *
 * <p>후보가 스무 개면 전량 실행을 스무 번 돌릴 수 없다 — 돈도 시간도 맞지 않는다. 그래서
 * 싸게 많이 보고, 좁혀서 더 보고, 마지막에 소수만 전수로 본다. 이 순서를 사람의 기억에
 * 맡기면 "표본 실행 결과로 최종 결정" 같은 일이 조용히 일어난다. 단계를 값으로 두면 표본 수와
 * 판정 가능 여부가 <b>단계에 묶여</b> 따라오고, 리포트가 자기가 몇 단계인지 말한다.
 *
 * <h2>판정은 마지막 단계에서만</h2>
 *
 * <p>{@link #FULL} 만 {@code sampleSize == ALL} 이고, 표본 실행은
 * {@link CellGoNoGo#evaluate}가 이미 {@code NOT_EVALUABLE} 로 막는다. 즉 "1·2단계는 판정을
 * 내지 않는다" 는 이 enum 의 약속이 아니라 기존 가드의 결과다 — 단계는 그 사실을 보기 좋게
 * 만들 뿐 새로 완화하지 않는다.
 */
enum BenchmarkStage {

    /**
     * 1단계 스크리닝 — 명부의 생성 후보 전부를 작은 표본으로 훑는다.
     *
     * <p>목적은 "누가 확실히 아닌가" 를 싸게 아는 것이다. 안전 지표를 주장하지 않는다.
     */
    SCREEN("1단계 스크리닝", 50, "명부의 생성 후보 전부"),

    /** 2단계 준결승 — 1단계 생존자를 더 큰 표본으로 다시 본다. */
    SEMIFINAL("2단계 준결승", 150, "1단계 생존자 (기본 6)"),

    /** 3단계 전량 — 역할별 결선 1~2개만 잠금 gold 전수로 돌린다. 여기서만 Go/No-Go 가 나온다. */
    FULL("3단계 전량", 0, "역할별 결선 1~2개");

    /** 표본을 쓰지 않는다는 뜻. {@link LockedEvalSet#CASES} 전량을 돈다. */
    static final int ALL = 0;

    private final String label;
    private final int sampleSize;
    private final String candidatePolicy;

    BenchmarkStage(String label, int sampleSize, String candidatePolicy) {
        this.label = label;
        this.sampleSize = sampleSize;
        this.candidatePolicy = candidatePolicy;
    }

    String label() {
        return label;
    }

    /** 이 단계의 기본 표본 수. {@link #ALL} 이면 전량. */
    int sampleSize() {
        return sampleSize;
    }

    String candidatePolicy() {
        return candidatePolicy;
    }

    /** 이 단계가 안전 판정을 낼 수 있는가. 전수 실행만 가능하다. */
    boolean canProduceVerdict() {
        return sampleSize == ALL;
    }

    /**
     * 이 단계에서 돌릴 후보.
     *
     * <p>1단계는 명부가 정한다 — 사람이 목록을 손으로 적으면 명부에 있는 후보가 조용히 빠진다.
     * 2·3단계는 <b>사람이 1단계 결과를 읽고</b> 고른 생존자를 {@code -PfrontierCandidates} 로
     * 넘겨야 한다. 자동으로 상위 N 개를 넘기지 않는 이유는, 탈락 규칙이 계산해 준 순위를 사람이
     * 확인하는 자리를 없애면 비용이 걸린 결정이 자동으로 흘러가기 때문이다.
     */
    List<String> candidates(CellCandidateRoster roster, List<String> explicit) {
        if (!explicit.isEmpty()) {
            return explicit;
        }
        if (this == SCREEN) {
            return roster.screeningCandidates();
        }
        throw new IllegalStateException("""
                %s 는 후보를 명시해야 한다 — -PfrontierCandidates="<후보1>,<후보2>"

                이 단계의 후보 정책: %s
                앞 단계의 탈락 규칙 결과를 읽고 사람이 고른다. 자동으로 상위 N 개를 넘기면
                비용이 걸린 결정에서 사람이 확인하는 자리가 사라진다.
                """.formatted(label, candidatePolicy));
    }

    /** {@code -Pstage=screen} 파싱. 모르는 이름을 조용히 기본값으로 접지 않는다. */
    static BenchmarkStage parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "알 수 없는 단계: '%s'. 가능한 값: screen / semifinal / full".formatted(raw), e);
        }
    }

    String describe() {
        return "%s — 표본 %s · 후보 %s · 안전 판정 %s".formatted(label,
                sampleSize == ALL ? "전량" : sampleSize + "건",
                candidatePolicy,
                canProduceVerdict() ? "가능" : "불가 (표본 실행)");
    }
}
