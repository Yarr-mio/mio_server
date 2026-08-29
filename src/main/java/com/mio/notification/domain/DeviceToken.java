package com.mio.notification.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "device_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DeviceToken {

    /**
     * 연속 실패 상한 (이슈 #497). 5분 주기 발송 기준으로 약 50분이면 도달한다.
     *
     * <p>일시적 네트워크 장애를 영구 실패로 오인하지 않을 만큼은 크고, 죽은 토큰이 하루 종일
     * APNs 를 두드리지 않을 만큼은 작은 값으로 잡았다.
     */
    public static final int MAX_CONSECUTIVE_FAILURES = 10;

    /** 상한 도달 후 재시도까지의 대기 시간. 이 동안 발송 대상에서 빠진다. */
    public static final Duration FAILURE_COOLDOWN = Duration.ofHours(24);


    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    /** ios / android */
    @Column(name = "platform", nullable = false)
    private String platform;

    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "is_valid", nullable = false)
    @Builder.Default
    private boolean isValid = true;

    /**
     * 연속 발송 실패 횟수 (이슈 #497).
     *
     * <p>발송 성공 시 0 으로 돌아간다. {@link #MAX_CONSECUTIVE_FAILURES} 에 도달하면
     * {@link #FAILURE_COOLDOWN} 동안 발송 대상에서 빠진다 — <b>토큰을 무효화하지는 않는다.</b>
     */
    @Column(name = "consecutive_failure_count", nullable = false)
    @Builder.Default
    private int consecutiveFailureCount = 0;

    @Column(name = "last_failure_reason")
    private String lastFailureReason;

    @Column(name = "last_failure_at")
    private OffsetDateTime lastFailureAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public void refreshToken(String newToken, String newAppVersion) {
        this.token = newToken;
        this.appVersion = newAppVersion;
        this.isValid = true;
        // 앱이 새 토큰을 등록했다는 것은 이전 실패 이력이 더는 이 토큰의 것이 아니라는 뜻이다.
        clearFailureState();
    }

    public void invalidate() {
        this.isValid = false;
    }

    /**
     * 발송 실패를 기록한다 (이슈 #497).
     *
     * <p>사유는 사후 추적용이다 — 토큰 등 민감 정보는 {@code PushSendResult} 단계에서 이미
     * 걸러져 들어온다.
     */
    public void recordSendFailure(String failureReason, OffsetDateTime failedAt) {
        this.consecutiveFailureCount++;
        this.lastFailureReason = failureReason;
        this.lastFailureAt = failedAt;
    }

    /** 발송에 성공하면 연속 실패 이력을 지운다. */
    public void recordSendSuccess() {
        clearFailureState();
    }

    /**
     * 지금 이 토큰으로 발송을 시도하면 안 되는지 (이슈 #497).
     *
     * <p>상한에 도달했더라도 <b>영구 제외가 아니라 쿨다운</b>이다. {@code apns-topic} 설정
     * 오류처럼 서버 쪽 원인이면 고친 뒤 스스로 회복돼야 하기 때문에, 별도 해제 작업 없이
     * {@link #FAILURE_COOLDOWN} 이 지나면 다시 대상이 된다. 그 결과 재시도 주기가
     * 5분 → 24시간으로 내려가고, 회복 가능성은 그대로 남는다.
     *
     * <p>카운터는 쿨다운이 지나도 초기화하지 않는다. 누적 실패 횟수 자체가 운영 판단
     * 재료이고, 초기화하려면 스케줄 잡이 하나 더 필요한데 그만한 값이 없다.
     */
    public boolean isSendSuppressed(OffsetDateTime now) {
        if (consecutiveFailureCount < MAX_CONSECUTIVE_FAILURES || lastFailureAt == null) {
            return false;
        }
        return lastFailureAt.isAfter(now.minus(FAILURE_COOLDOWN));
    }

    private void clearFailureState() {
        this.consecutiveFailureCount = 0;
        this.lastFailureReason = null;
        this.lastFailureAt = null;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
