package com.mio.notification.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.notification.domain.NotificationSetting;
import com.mio.notification.dto.NotificationSettingResponse;
import com.mio.notification.dto.NotificationSettingUpdateRequest;
import com.mio.notification.repository.NotificationSettingRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final NotificationSettingRepository notificationSettingRepository;
    private final UserRepository userRepository;

    @Transactional
    public NotificationSettingResponse getOrCreate(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        NotificationSetting setting = notificationSettingRepository.findByUser_Id(userId)
                .orElseGet(() -> notificationSettingRepository.save(
                        NotificationSetting.builder()
                                .user(user)
                                .build()
                ));

        return NotificationSettingResponse.from(setting);
    }

    @Transactional
    public NotificationSettingResponse update(UUID userId, NotificationSettingUpdateRequest request) {
        NotificationSetting setting = notificationSettingRepository.findByUser_Id(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));

        LocalTime morningTime = parseTime(request.checkinTime() == null ? null : request.checkinTime().morning());
        LocalTime afternoonTime = parseTime(request.checkinTime() == null ? null : request.checkinTime().afternoon());
        LocalTime eveningTime = parseTime(request.checkinTime() == null ? null : request.checkinTime().evening());

        setting.update(
                null,
                request.checkinEnabled(),
                morningTime,
                afternoonTime,
                eveningTime,
                request.characterEnabled(),
                request.reportEnabled(),
                null
        );

        return NotificationSettingResponse.from(setting);
    }

    private LocalTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
