package com.mio.ai.memory.ontology;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "behavior_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BehaviorTemplate {

    @Id
    private String code;

    @Column(nullable = false)
    private String category;

    @Column(name = "action_text_ko", nullable = false)
    private String actionTextKo;

    @Column(name = "intervention_kind", nullable = false)
    private String interventionKind;

    // 이슈 #374 — 저장 타입이 String[]인 이유: List<String>으로 두면 안 된다.
    // 같은 페르시스턴스 유닛의 UserOnboardingAnswer.concernTypes가 List<String>을
    // SqlTypes.JSON으로 매핑하는데, Hibernate는 한 Java 타입의 BasicType을 전역 단일
    // 슬롯에 등록한다. 그래서 두 매핑이 경합해 먼저 처리된 쪽이 이기고, 엔티티 처리
    // 순서(= jar 엔트리 스캔 순서, 빌드마다 달라짐)에 따라 이 컬럼이 jsonb로 해석돼
    // 기동 시 스키마 검증이 비결정적으로 실패했다(2026-08-06/08-07 프로덕션 장애).
    // String[]은 List<String>과 다른 Java 타입이라 그 경합 자체가 사라진다.
    // 게터는 List<String>을 유지해 호출부를 그대로 둔다.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fits_distortions", columnDefinition = "text[]")
    private String[] fitsDistortions;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fits_emotions", columnDefinition = "text[]")
    private String[] fitsEmotions;

    @Column(nullable = false)
    private Integer difficulty;

    @Column(name = "estimated_minutes", nullable = false)
    private Integer estimatedMinutes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String prerequisites;

    /** 컬럼이 NULL이면 null을 그대로 반환한다(호출부가 null을 분기하고 있다). */
    public List<String> getFitsDistortions() {
        return fitsDistortions == null ? null : Arrays.stream(fitsDistortions).toList();
    }

    public List<String> getFitsEmotions() {
        return fitsEmotions == null ? null : Arrays.stream(fitsEmotions).toList();
    }
}
