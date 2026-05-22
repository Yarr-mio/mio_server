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

    @Column(name = "avg_emotion_score")
    private Double avgEmotionScore;

    @Column(name = "emotion_scores", columnDefinition = "jsonb")
    private String emotionScores;

    @Column(name = "distortion_distribution", columnDefinition = "jsonb")
    private String distortionDistribution;

    @Column(name = "narrative")
    private String narrative;

    @Column(name = "coaching_direction")
    private String coachingDirection;

    @Column(name = "is_partial", nullable = false)
    private boolean isPartial;

    @Column(name = "generated_at")
    private OffsetDateTime generatedAt;
}
