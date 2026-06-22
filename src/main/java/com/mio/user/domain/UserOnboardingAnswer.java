package com.mio.user.domain;

import com.mio.onboarding.dto.CharacterRecommendationDto;
import com.mio.onboarding.dto.QuestionResponse;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "user_onboarding_answers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserOnboardingAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** step 1 완료 시 저장: happy/calm/anxious/sad/angry/ashamed/numb/tired/confused */
    @Column(name = "emotion_state")
    private String emotionState;

    /** step 2 완료 시 저장 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "concern_types", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> concernTypes = new ArrayList<>();

    /** step 3 완료 시 저장: empathetic/analytical/solution/balanced */
    @Column(name = "preferred_style")
    private String preferredStyle;

    /** step 3 완료 시 AI 추천 결과 저장 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "character_recommendations", columnDefinition = "jsonb")
    @Builder.Default
    private List<CharacterRecommendationDto> characterRecommendations = new ArrayList<>();

    /** 개별 질문 답변 목록 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "responses", columnDefinition = "jsonb")
    @Builder.Default
    private List<QuestionResponse> responses = new ArrayList<>();

    /** step 3 완료 시점 */
    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    public void updateStep1(String emotionState, List<QuestionResponse> responses) {
        this.emotionState = emotionState;
        this.responses = responses != null ? responses : List.of();
    }

    public void updateStep2(List<String> concernTypes, List<QuestionResponse> responses) {
        this.concernTypes = concernTypes != null ? concernTypes : List.of();
        this.responses = responses != null ? responses : List.of();
    }

    public void updateStep3(String preferredStyle, List<CharacterRecommendationDto> characterRecommendations, List<QuestionResponse> responses) {
        this.preferredStyle = preferredStyle;
        this.characterRecommendations = characterRecommendations != null ? characterRecommendations : List.of();
        this.responses = responses != null ? responses : List.of();
        this.submittedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
