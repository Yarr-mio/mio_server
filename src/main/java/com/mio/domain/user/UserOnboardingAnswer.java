package com.mio.domain.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
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
    @Column(name = "concern_types", columnDefinition = "jsonb")
    @Builder.Default
    private String concernTypes = "[]";

    /** step 3 완료 시 저장: empathetic/analytical/solution/balanced */
    @Column(name = "preferred_style")
    private String preferredStyle;

    /** step 3 완료 시 AI 추천 결과 저장 */
    @Column(name = "character_recommendations", columnDefinition = "jsonb")
    @Builder.Default
    private String characterRecommendations = "[]";

    /** 개별 질문 답변 목록 */
    @Column(name = "responses", columnDefinition = "jsonb")
    @Builder.Default
    private String responses = "[]";

    /** step 3 완료 시점 */
    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;
}
