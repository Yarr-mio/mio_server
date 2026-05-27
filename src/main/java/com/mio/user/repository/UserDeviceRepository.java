package com.mio.user.repository;

import com.mio.user.domain.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {

    Optional<UserDevice> findByUser_IdAndDeviceId(UUID userId, String deviceId);

    void deleteAllByUser_Id(UUID userId);
}
