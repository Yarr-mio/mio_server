package com.mio.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 한 사용자의 데이터 삭제 작업 (이슈 #373, 로드맵 §12 P0-6).
 *
 * <p>탈퇴는 지금까지 {@code users.deleted_at} 하나로만 표현됐다. 그래서 "삭제가 어디까지
 * 갔는가" 를 물어볼 수 없었고, {@code WithdrawResponse} 가 돌려주던
 * {@code hard_delete_scheduled_at} 은 상태가 아니라 <b>약속</b>이었다 — 실제로 그날
 * 지워졌는지는 아무 데도 남지 않았다.
 *
 * <p>저장소별 시각을 따로 두는 이유는 재시도 때문이다. 캐시는 지웠는데 DB 에서 실패한
 * 경우, 다음 시도가 캐시를 다시 지울 필요는 없고 무엇보다 <b>어디서 막혔는지</b>가 남아야
 * 한다.
 */
@Entity
@Table(name = "data_deletion_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataDeletionRequest {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "status", nullable = false)
    private DeletionStatus status = DeletionStatus.PENDING;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "cache_purged_at")
    private OffsetDateTime cachePurgedAt;

    @Column(name = "database_purged_at")
    private OffsetDateTime databasePurgedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (requestedAt == null) {
            requestedAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 탈퇴 접수. 유예 기간이 끝나는 시각을 그때 계산해 고정한다. */
    public static DataDeletionRequest open(UUID userId, OffsetDateTime scheduledAt) {
        DataDeletionRequest request = new DataDeletionRequest();
        request.userId = userId;
        request.status = DeletionStatus.PENDING;
        request.scheduledAt = scheduledAt;
        return request;
    }

    /** 캐시를 지웠다. DB 삭제 전에 기록해 두면 재시도가 어디서 막혔는지 알 수 있다. */
    public void markCachePurged() {
        this.cachePurgedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void markDatabasePurged() {
        this.databasePurgedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void beginAttempt() {
        this.status = DeletionStatus.IN_PROGRESS;
        this.attempts += 1;
    }

    public void complete() {
        this.status = DeletionStatus.COMPLETED;
        this.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.lastError = null;
    }

    /**
     * 재시도 상한까지 실패했다.
     *
     * <p>{@code pending} 으로 되돌리지 않는다. 되돌리면 영원히 재시도되면서 아무도 문제를
     * 모른다. 사유 없이 {@code failed} 로 두지도 않는다 — DB CHECK 제약이 그것을 막는다.
     */
    public void fail(String reason) {
        this.status = DeletionStatus.FAILED;
        this.completedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.lastError = reason != null && !reason.isBlank() ? truncate(reason) : "unknown";
    }

    /** 실패했지만 아직 재시도 여지가 있다. 사유만 남기고 대기 상태로 되돌린다. */
    public void deferWith(String reason) {
        this.status = DeletionStatus.PENDING;
        this.lastError = reason != null && !reason.isBlank() ? truncate(reason) : "unknown";
    }

    public boolean isDue(OffsetDateTime now) {
        return !scheduledAt.isAfter(now);
    }

    /** 예외 메시지가 길면 스택 전체가 컬럼에 들어간다. 원인 파악에 필요한 앞부분만 남긴다. */
    private static String truncate(String reason) {
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}
