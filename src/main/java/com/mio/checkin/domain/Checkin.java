package com.mio.checkin.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import com.mio.common.AppConstants;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "checkins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Checkin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "character_id")
    private String characterId;

    /** morning / afternoon / evening */
    @Column(name = "time_of_day", nullable = false)
    private String timeOfDay;

    /**
     * 감정 유형: happy/calm/anxious/sad/angry/ashamed/numb/tired/confused
     * UNIQUE 제약: (user_id, time_of_day, checkin_date)
     */
    @Column(name = "emotion_type", nullable = false)
    private String emotionType;

    /** 감정 강도 1~5 (체크인용). CBT 측정용 emotion_score 0~100 과 혼용 금지 */
    @Column(name = "condition_score", nullable = false)
    private int conditionScore;

    @Column(name = "checkin_date", nullable = false, updatable = false)
    private LocalDate checkinDate;

    @Column(name = "memo_ciphertext")
    private byte[] memoCiphertext;

    @Column(name = "memo_dek_id")
    private String memoDekId;

    @Column(name = "ai_response")
    private String aiResponse;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public void update(String emotionType, Integer conditionScore, byte[] memoCiphertext, String memoDekId, boolean updateMemo) {
        if (emotionType != null) this.emotionType = emotionType;
        if (conditionScore != null) this.conditionScore = conditionScore;
        if (updateMemo) {
            this.memoCiphertext = memoCiphertext;
            this.memoDekId = memoDekId;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (checkinDate == null) {
            checkinDate = LocalDate.now(AppConstants.ZONE);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
