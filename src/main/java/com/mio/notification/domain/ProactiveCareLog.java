package com.mio.notification.domain;

import com.mio.user.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "proactive_care_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProactiveCareLog {

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_OPENED = "OPENED";
    public static final String STATUS_FAILED = "FAILED";
    /** 보낼 유효 디바이스 토큰이 없어 발송 자체가 일어나지 않은 상태. */
    public static final String STATUS_NO_DEVICE = "NO_DEVICE";
    /**
     * 발송 여부 불명 — 타임아웃 등으로 게이트웨이 응답을 받지 못했다.
     * 실제로 나갔을 수도 있으므로 재발송하면 중복 도착이 된다.
     */
    public static final String STATUS_UNCONFIRMED = "UNCONFIRMED";

    /**
     * <b>실제로 발송된 것이 확인된</b> 상태 집합 — 일일 발송 한도 산정의 기준이다.
     *
     * <p>{@code OPENED} 를 포함하는 이유: {@link #markOpened()} 가 {@code SENT} 를 {@code OPENED} 로
     * 바꾸므로, {@code SENT} 만 보면 사용자가 열람한 알림이 한도에서 빠진다.
     *
     * <p>{@code DELIVERED} 는 현재 이 값을 기록하는 코드 경로가 없다(APNs·FCM 도달 확인 콜백 미구현).
     * 나중에 도달 확인이 붙었을 때 누락되지 않도록 방어적으로 포함해 둔다.
     *
     * <p>{@code UNCONFIRMED} 는 <b>포함하지 않는다</b> — 실제로 나갔는지 모르는 건을 한도에서 차감하면
     * 받지도 못한 알림이 그날의 몫을 잡아먹는다 (이슈 #390 이 없애려던 문제와 같은 형태).
     */
    public static final Set<String> DELIVERED_STATUSES = Set.of(STATUS_SENT, STATUS_DELIVERED, STATUS_OPENED);

    /**
     * <b>24시간 재발송을 억제하는</b> 상태 집합 — {@link #DELIVERED_STATUSES} 와 기준이 다르다.
     *
     * <p>억제 판정의 질문은 "이걸 다시 보내면 유저가 두 번 받는가?"이다. 한도 판정의 질문
     * ("실제로 몇 건 나갔는가?")과 답이 갈리는 지점이 {@code UNCONFIRMED} 다. 발송 여부가 불명이면
     * 이미 나갔을 수 있으므로 재발송하지 않는다. 반대로 {@code FAILED}(게이트웨이가 명시적으로 거절)와
     * {@code NO_DEVICE}(보낼 단말 없음)는 확실히 미발송이므로 억제하지 않고 재시도한다 (이슈 #389).
     */
    public static final Set<String> SUPPRESSING_STATUSES =
            Set.of(STATUS_SENT, STATUS_DELIVERED, STATUS_OPENED, STATUS_UNCONFIRMED);

    /** {@code OPENED} 로 전이할 수 있는 상태 — 실제로 발송된 알림만 열람될 수 있다. */
    private static final Set<String> OPENABLE_STATUSES = Set.of(STATUS_SENT, STATUS_DELIVERED);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * [v2.4] CHECK 제약 7종:
     * checkin_reminder_morning / checkin_reminder_afternoon / checkin_reminder_evening /
     * todo_incomplete / negative_emotion_streak / crisis_detected / report_weekly
     */
    @Column(name = "trigger_code", nullable = false)
    private String triggerCode;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    /** SENT / DELIVERED / OPENED / FAILED / NO_DEVICE / UNCONFIRMED (뒤 2개는 내부 전용) */
    @Column(name = "notification_status", nullable = false)
    @Builder.Default
    private String notificationStatus = STATUS_SENT;

    /**
     * 발송 실패 사유 (APNs HTTP 상태·reason, FCM 오류 코드).
     * 일부 단말만 실패한 부분 실패도 기록한다 — 이 경우 상태는 {@code SENT} 이면서 사유가 함께 남는다.
     * 모든 단말이 성공하면 null.
     */
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    /** tapped / dismissed */
    @Column(name = "response_action")
    private String responseAction;

    /**
     * 열람 처리. 실제로 발송된 알림({@link #OPENABLE_STATUSES})에서만 {@code OPENED} 로 전이한다.
     *
     * <p>가드가 없으면 {@code NO_DEVICE}·{@code FAILED} 로그도 {@code OPENED} 가 되어
     * {@link #DELIVERED_STATUSES} 에 편입되고, 한 번도 발송되지 않은 알림이 재발송 억제와
     * 일일 한도에 "실제 발송분"으로 새어 들어간다 (이슈 #387 의 재현 경로).
     * 알림 이력 API 가 미발송 건까지 내려주고 FE 가 일괄 읽음 처리를 하면 실제로 도달할 수 있다.
     *
     * <p>전이 불가 상태에서 호출되면 예외 대신 무시한다. 일괄 읽음 처리에서 항목 하나 때문에
     * 요청 전체가 실패하는 것을 막기 위해서다. 호출부는 변경되지 않은 현재 상태를 응답으로 돌려받는다.
     */
    public void markOpened() {
        if (!OPENABLE_STATUSES.contains(this.notificationStatus) || this.respondedAt != null) {
            return;
        }
        this.notificationStatus = STATUS_OPENED;
        this.respondedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.responseAction = "tapped";
    }

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
