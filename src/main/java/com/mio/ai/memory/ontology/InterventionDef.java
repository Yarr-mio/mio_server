package com.mio.ai.memory.ontology;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fits_distortions", columnDefinition = "text[]")
    private List<String> fitsDistortions;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fits_emotions", columnDefinition = "text[]")
    private List<String> fitsEmotions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contraindicated_when", columnDefinition = "jsonb")
    private String contraindicatedWhen;

    @Column(nullable = false)
    private Integer difficulty;

    @Column(name = "expected_duration_min", nullable = false)
    private Integer expectedDurationMin;
}
