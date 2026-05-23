package com.mio.domain.report;

import com.mio.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "weekly_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WeeklyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)
    private LocalDate weekEnd;

    @Column(name = "checkin_count", nullable = false)
    private int checkinCount;

    /** CBT 측정용 0~100 (FLOAT). emoji_score 1~5 와 혼용 금지 */
    @Column(name = "avg_emotion_score")
    private Double avgEmotionScore;

    /** 날짜별 avg_emotion_score 맵 { "YYYY-MM-DD": Float } */
    @Column(name = "emotion_scores", columnDefinition = "jsonb")
    @Builder.Default
    private String emotionScores = "{}";

    /**
     * API 응답 시 distortion_top3 배열로 변환:
     * SELECT key AS type, value::INT AS count
     * FROM jsonb_each_text(distortion_distribution)
     * ORDER BY count DESC LIMIT 3
     */
    @Column(name = "distortion_distribution", columnDefinition = "jsonb")
    @Builder.Default
    private String distortionDistribution = "{}";

    /** 2차 개발: AI 생성 주간 코칭 내러티브 */
    @Column(name = "narrative")
    private String narrative;

    /** 2차 개발: AI 생성 다음 주 코칭 방향 */
    @Column(name = "coaching_direction")
    private String coachingDirection;

    /** PENDING / GENERATED / INSUFFICIENT_DATA */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "is_partial", nullable = false)
    private boolean isPartial;

    @Column(name = "generated_at")
    private OffsetDateTime generatedAt;
}
