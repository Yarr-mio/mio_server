package com.mio.ai.qa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 한 실행 안에서 돌릴 <b>셀 × 상위 모델 후보</b> 조합.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>registry 는 역할당 모델 ID 를 하나만 핀한다. 그래서 상위 모델 후보 N 개를 비교하려면
 * 실행을 N 번 나눠야 했는데, 그렇게 나온 결과는 <b>같은 실행이 아니므로 비교할 수 없다</b> —
 * {@link RunIdentity} 가 정확히 그 비교를 막는다. 두 규칙이 서로를 막는 상태였다.
 *
 * <p>해결은 실행을 나누지 않는 것이다. 상위 모델을 쓰는 셀(B·C·D·E)을 후보 수만큼 <b>변형</b>
 * 으로 펼쳐, 같은 기준선 A·같은 케이스 목록·같은 {@link RunIdentity} 아래에서 한 번에 돌린다.
 * 그러면 후보 간 비교도, 후보 대 기준선 비교도 같은 실행 안에서 끝난다.
 *
 * <h2>후보를 주지 않으면</h2>
 *
 * <p>{@code frontierCandidate} 가 {@code null} 인 변형은 기존 동작 그대로다 — registry 가
 * {@code -PcellModels} 로 핀한 값을 쓰고, 핀이 없으면 fail-closed 로 막힌다.
 *
 * @param frontierCandidate 이 변형이 상위 모델 역할 전부에 쓸 후보 ID. {@code null} 이면 기존 핀
 */
record CellVariant(BenchmarkCell cell, String frontierCandidate) {

    /** 후보 스크리닝이 아닌 단일 변형. */
    static CellVariant of(BenchmarkCell cell) {
        return new CellVariant(cell, null);
    }

    /** 리포트·아카이브·manifest 가 쓰는 이름. 후보가 다르면 이름이 다르다. */
    String label() {
        return frontierCandidate == null ? cell.name() : cell.name() + "/" + frontierCandidate;
    }

    /** 파일명 조각. {@code /} 는 경로 구분자라 그대로 쓸 수 없다. */
    String fileLabel() {
        return label().toLowerCase(java.util.Locale.ROOT).replace('/', '-').replace(' ', '-');
    }

    boolean isScreeningVariant() {
        return frontierCandidate != null;
    }

    /** manifest 의 {@code cell} 값. 후보까지 포함해 기록만 보고도 어느 후보인지 알 수 있게 한다. */
    String manifestValue() {
        return frontierCandidate == null
                ? cell.manifestValue()
                : "%s [상위 모델 후보 %s]".formatted(cell.manifestValue(), frontierCandidate);
    }

    /**
     * {@code -PfrontierCandidates="a,b,c"} 파싱.
     *
     * <p>{@link BenchmarkCell#parse(String)} 와 같은 이유로 빈 항목을 조용히 넘기지 않는다.
     * 중복은 제거한다 — 같은 후보를 두 번 돌리면 청구서만 두 배가 된다.
     */
    static List<String> parseCandidates(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String candidate = raw.trim();
            if (candidate.isEmpty()) {
                throw new IllegalArgumentException(
                        "frontierCandidates 에 빈 항목이 있다: '%s' — 오타 하나가 조용히 무시되면 "
                                .formatted(csv) + "실행하지 않은 후보가 비교표에 없는 이유를 알 수 없다");
            }
            ordered.add(candidate);
        }
        return List.copyOf(ordered);
    }

    /**
     * 셀 목록 × 후보 목록을 실행 변형으로 펼친다.
     *
     * <p>상위 모델 역할이 없는 셀(A)은 후보와 무관하므로 <b>한 번만</b> 나온다. 기준선을
     * 후보 수만큼 돌리면 같은 값을 반복 측정하며 돈만 쓴다.
     *
     * <p>후보 목록이 비면 셀당 변형 하나 — 기존 단일 후보 실행과 완전히 같다.
     */
    static List<CellVariant> expand(List<BenchmarkCell> cells, List<String> candidates) {
        List<CellVariant> variants = new ArrayList<>();
        for (BenchmarkCell cell : cells) {
            if (candidates.isEmpty() || cell.frontierRoles().isEmpty()) {
                variants.add(of(cell));
                continue;
            }
            candidates.forEach(candidate -> variants.add(new CellVariant(cell, candidate)));
        }
        return List.copyOf(variants);
    }

    /** 스크리닝 표에 실릴 후보 순서. 실행 순서가 아니라 선언 순서를 쓴다. */
    static List<String> candidatesOf(List<CellVariant> variants) {
        return variants.stream()
                .map(CellVariant::frontierCandidate)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public String toString() {
        return label();
    }

    /** 진단용 — 셀 이름만 적힌 후보 목록을 사람이 읽을 수 있게. */
    static String describe(List<CellVariant> variants) {
        return Arrays.toString(variants.stream().map(CellVariant::label).toArray());
    }
}
