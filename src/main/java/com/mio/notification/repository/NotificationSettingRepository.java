package com.mio.notification.repository;

import com.mio.notification.domain.NotificationSetting;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;
import java.util.UUID;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, UUID> {

    Optional<NotificationSetting> findByUser_Id(UUID userId);

    @EntityGraph(attributePaths = "user")
    Slice<NotificationSetting> findByNotificationAgreeTrue(Pageable pageable);
}
