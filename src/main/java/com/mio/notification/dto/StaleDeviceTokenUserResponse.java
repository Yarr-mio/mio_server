package com.mio.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mio.notification.repository.DeviceTokenRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 유효 디바이스 토큰이 0개라 알림이 끊긴 유저 (이슈 #392).
 *
 * <p>토큰 값 자체는 담지 않는다. 운영자가 재등록을 유도할 대상을 식별하는 데 필요한 정보만 노출한다.
 */
public record StaleDeviceTokenUserResponse(
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("last_token_updated_at") OffsetDateTime lastTokenUpdatedAt,
        @JsonProperty("invalid_token_count") long invalidTokenCount
) {

    public static StaleDeviceTokenUserResponse from(DeviceTokenRepository.UserWithoutValidToken row) {
        return new StaleDeviceTokenUserResponse(
                row.getUserId(), row.getLastTokenUpdatedAt(), row.getInvalidTokenCount());
    }
}
