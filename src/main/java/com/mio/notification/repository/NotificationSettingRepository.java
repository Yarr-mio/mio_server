package com.mio.notification.repository;

import com.mio.notification.domain.NotificationSetting;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, UUID> {

    Optional<NotificationSetting> findByUser_Id(UUID userId);

    /**
     * 스케줄 발송 대상 조회 (이슈 #388).
     *
     * <p>탈퇴(soft delete)한 유저의 {@code notification_settings} 행은 그대로 남기 때문에,
     * 동의 플래그만 보면 탈퇴자에게 계속 푸시가 나간다. 개인정보 처리 정지 요구에 반하므로
     * {@code users}를 조인해 상태로 거른다.
     *
     * <ul>
     *   <li>{@code deleted_at IS NULL} — soft delete 유저 제외</li>
     *   <li>{@code status NOT IN ('DELETED', 'SUSPENDED')} — 탈퇴/정지 유저 제외</li>
     * </ul>
     *
     * <p>설정 행을 비활성화하지 않고 조인 조건으로만 거르는 이유는, 계정 복구·재가입 시
     * 유저가 지정했던 알림 시각을 되살릴 수 있어야 하기 때문이다.
     */
    @EntityGraph(attributePaths = "user")
    @Query("""
            select ns from NotificationSetting ns
            join ns.user u
            where ns.notificationAgree = true
              and u.deletedAt is null
              and u.status not in ('DELETED', 'SUSPENDED')
            """)
    Slice<NotificationSetting> findSendableTargets(Pageable pageable);
}
