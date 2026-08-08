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
@Table(name = "emotion_def")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmotionDef {

    @Id
    private String code;

    @Column(name = "ko_label", nullable = false)
    private String koLabel;

    @Column(nullable = false)
    private Double valence;

    @Column(nullable = false)
    private Double arousal;

    @Column(nullable = false)
    private String family;

    // 이슈 #374 — String[]로 저장하는 이유는 BehaviorTemplate 의 같은 주석 참고.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "escalation_to", columnDefinition = "text[]")
    private String[] escalationTo;

    @Column(name = "crisis_risk_weight", nullable = false)
    private Double crisisRiskWeight;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acknowledgment_phrases", columnDefinition = "jsonb")
    private String acknowledgmentPhrases;

    /** 컬럼이 NULL이면 null을 그대로 반환한다. */
    public List<String> getEscalationTo() {
        return escalationTo == null ? null : Arrays.stream(escalationTo).toList();
    }
}
