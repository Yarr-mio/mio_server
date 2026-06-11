package com.mio.ai.memory.ontology;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
@Table(name = "cbt_distortion_def")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CbtDistortionDef {

    @Id
    private String code;

    @Column(name = "policy_code", nullable = false)
    private String policyCode;

    @Column(name = "ko_label", nullable = false)
    private String koLabel;

    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "typical_triggers", columnDefinition = "text[]")
    private List<String> typicalTriggers;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cooccur_codes", columnDefinition = "text[]")
    private List<String> cooccurCodes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "counter_questions", columnDefinition = "jsonb")
    private String counterQuestions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reframe_examples", columnDefinition = "jsonb")
    private String reframeExamples;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recommended_actions", columnDefinition = "text[]")
    private List<String> recommendedActions;
}
