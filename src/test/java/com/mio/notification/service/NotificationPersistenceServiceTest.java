package com.mio.notification.service;

import com.mio.notification.domain.DeviceToken;
import com.mio.notification.domain.ProactiveCareLog;
import com.mio.notification.repository.DeviceTokenRepository;
import com.mio.notification.repository.ProactiveCareLogRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPersistenceServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private DeviceTokenRepository deviceTokenRepository;
    @Mock private ProactiveCareLogRepository proactiveCareLogRepository;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private NotificationPersistenceService notificationPersistenceService;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        notificationPersistenceService = new NotificationPersistenceService(
                Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"), ZoneOffset.of("+09:00")),
                userRepository,
                deviceTokenRepository,
                proactiveCareLogRepository,
                stringRedisTemplate
        );
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("social-id")
                .privacyConsent(true)
                .build();
        setField(user, "id", userId);
    }

    @Test
    @DisplayName("발송 결과 저장 시 토큰 무효화, 로그 저장, 일일 카운트 증가를 처리한다")
    void persistNotificationResult_savesLogAndInvalidatesTokens() {
        UUID tokenId = UUID.randomUUID();
        DeviceToken token = DeviceToken.builder()
                .user(user)
                .deviceId("device-1")
                .platform("android")
                .token("fcm-token")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceTokenRepository.findById(tokenId)).thenReturn(Optional.of(token));
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(stringRedisTemplate.expireAt(anyString(), any(Date.class))).thenReturn(true);

        notificationPersistenceService.persistNotificationResult(
                userId,
                "todo_incomplete",
                true,
                List.of(tokenId),
                true
        );

        assertThat(token.isValid()).isFalse();
        verify(deviceTokenRepository).save(token);

        ArgumentCaptor<ProactiveCareLog> captor = ArgumentCaptor.forClass(ProactiveCareLog.class);
        verify(proactiveCareLogRepository).save(captor.capture());
        assertThat(captor.getValue().getTriggerCode()).isEqualTo("todo_incomplete");
        assertThat(captor.getValue().getNotificationStatus()).isEqualTo("SENT");
        verify(valueOperations).increment(anyString());
    }

    private void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
