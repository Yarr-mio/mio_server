package com.mio.notification.repository;

import com.mio.notification.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    List<DeviceToken> findByUser_IdAndIsValidTrue(UUID userId);

    Optional<DeviceToken> findByUser_IdAndDeviceId(UUID userId, String deviceId);

    Optional<DeviceToken> findByUser_IdAndToken(UUID userId, String token);
}
