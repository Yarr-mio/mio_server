package com.mio.ai.memory.ontology;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fits_distortions", columnDefinition = "text[]")
    private List<String> fitsDistortions;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "fits_emotions", columnDefinition = "text[]")
    private List<String> fitsEmotions;

    @Column(nullable = false)
    private Integer difficulty;

    @Column(name = "estimated_minutes", nullable = false)
    private Integer estimatedMinutes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String prerequisites;
}
