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
@Table(name = "intervention_def")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterventionDef {

    @Id
    private String code;

    @Column(nullable = false)
    private String kind;

    @Column(name = "ko_label", nullable = false)
    private String koLabel;

    // 이슈 #374 — String[]로 저장하는 이유는 BehaviorTemplate 의 같은 주석 참고.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fits_distortions", columnDefinition = "text[]")
    private String[] fitsDistortions;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fits_emotions", columnDefinition = "text[]")
    private String[] fitsEmotions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contraindicated_when", columnDefinition = "jsonb")
    private String contraindicatedWhen;

    @Column(nullable = false)
    private Integer difficulty;

    @Column(name = "expected_duration_min", nullable = false)
    private Integer expectedDurationMin;

    /** 컬럼이 NULL이면 null을 그대로 반환한다. */
    public List<String> getFitsDistortions() {
        return fitsDistortions == null ? null : Arrays.stream(fitsDistortions).toList();
    }

    public List<String> getFitsEmotions() {
        return fitsEmotions == null ? null : Arrays.stream(fitsEmotions).toList();
    }
}
