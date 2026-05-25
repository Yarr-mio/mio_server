package com.mio.notification.repository;

import com.mio.notification.domain.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, UUID> {

    Optional<NotificationSetting> findByUser_Id(UUID userId);

    @Query("""
            SELECT ns
            FROM NotificationSetting ns
            JOIN FETCH ns.user
            WHERE ns.notificationAgree = true
            """)
    List<NotificationSetting> findAllNotificationAgreed();
}
