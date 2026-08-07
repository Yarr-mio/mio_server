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

    // 이슈 #374 — String[]로 저장하는 이유는 BehaviorTemplate 의 같은 주석 참고.
    // List<String>은 UserOnboardingAnswer.concernTypes(JSON 매핑)와 Java 타입이 같아
    // 엔티티 처리 순서에 따라 jsonb로 오염된다.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "typical_triggers", columnDefinition = "text[]")
    private String[] typicalTriggers;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "cooccur_codes", columnDefinition = "text[]")
    private String[] cooccurCodes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "counter_questions", columnDefinition = "jsonb")
    private String counterQuestions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reframe_examples", columnDefinition = "jsonb")
    private String reframeExamples;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recommended_actions", columnDefinition = "text[]")
    private String[] recommendedActions;

    /** 컬럼이 NULL이면 null을 그대로 반환한다(호출부가 null을 분기하고 있다). */
    public List<String> getTypicalTriggers() {
        return typicalTriggers == null ? null : Arrays.stream(typicalTriggers).toList();
    }

    public List<String> getCooccurCodes() {
        return cooccurCodes == null ? null : Arrays.stream(cooccurCodes).toList();
    }

    public List<String> getRecommendedActions() {
        return recommendedActions == null ? null : Arrays.stream(recommendedActions).toList();
    }
}
