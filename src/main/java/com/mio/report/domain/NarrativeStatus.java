package com.mio.report.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 리포트 내러티브의 생성 상태 (API 명세 `08_Report_리포트.md` v1.1, 이슈 #419).
 *
 * <p><b>core {@code status} 와 독립이다.</b> 집계는 SQL 한 번이면 어느 기간이든 낼 수 있고
 * 내러티브만 배치 산출물이므로, 내러티브가 없다는 사실이 리포트 전체를 {@code PENDING} 이나
 * 오류로 만들어서는 안 된다. 두 상태를 한 필드에 겹쳐 두면 클라이언트가
 * "집계는 보여줄 수 있는데 내러티브만 아직 없음" 을 표현할 방법을 잃는다.
 */
public enum NarrativeStatus {

    /** 저장된 내러티브를 반환한다. */
    READY,

    /** 배치 생성 대기·진행 중. 기다리면 채워진다. core 집계는 반환 가능. */
    PENDING,

    /**
     * 해당 기간이 생성 대상이 아니거나 저장 artifact 가 없다.
     *
     * <p>{@link #PENDING} 과 나누는 기준은 "기다리면 채워지는가" 다. 지난 주차는 아직 배치가
     * 돌 기회가 남아 있지만, 그보다 과거 주차는 영영 채워지지 않는다. 둘을 구분하지 않으면
     * 클라이언트가 과거 리포트에서 무한히 로딩 UI 를 띄우게 된다.
     */
    UNAVAILABLE,

    /**
     * 내러티브 생성이 terminal 실패로 끝났다. core 집계는 반환 가능.
     *
     * <p>배치가 실패를 상태로 남기기 시작하면 매핑한다. 현재 {@code WeeklyReflectionJob} 은
     * LLM 실패를 로그로만 남기고 컬럼에 기록하지 않아, 조회 시점에는 "아직 안 만들어짐" 과
     * 구별할 수 없다 — 그래서 지금은 이 값을 내보내지 않는다.
     */
    FAILED;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase();
    }
}
