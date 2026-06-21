package com.mio.checkin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.checkin.domain.Checkin;
import com.mio.checkin.dto.*;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.common.AppConstants;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinService {

    private static final Set<String> VALID_EMOTIONS = Set.of(
            "happy", "calm", "anxious", "sad", "angry", "ashamed", "numb", "tired", "confused"
    );
    private static final Set<String> VALID_SLOTS = Set.of("morning", "afternoon", "evening");
    private static final List<String> ALL_SLOTS = List.of("morning", "afternoon", "evening");
    private static final int PAGE_SIZE = 20;
    private static final long IDEMPOTENCY_TTL_SECONDS = 86400L;
    private static final int RATE_LIMIT_MAX = 4;
    private static final long RATE_LIMIT_TTL_SECONDS = 3600L;
    private final CheckinRepository checkinRepository;
    private final UserRepository userRepository;
    private final MessageEncryptor messageEncryptor;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CheckinAiResponseGenerator aiResponseGenerator;

    @Transactional
    public CheckinCreateResponse submit(UUID userId, CheckinRequest request, String idempotencyKey) {
        validateSlot(request.timeOfDay());
        validateEmotion(request.emotionType());
        checkRateLimit(userId);

        if (idempotencyKey != null) {
            String cached = redisTemplate.opsForValue().get(idempotencyKey(idempotencyKey));
            if (cached != null) {
                CheckinCreateResponse cachedResponse = deserializeResponse(cached);
                if (cachedResponse != null) return cachedResponse;
                // 역직렬화 실패 → 캐시 미스로 처리하여 정상 플로우 진행
            }
        }

        LocalDate today = LocalDate.now(AppConstants.ZONE);
        if (checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(userId, today, request.timeOfDay())) {
            throw new BusinessException(ErrorCode.ALREADY_CHECKED_IN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!user.getSignupStep().isOnboardingComplete()) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }

        byte[] ciphertext = null;
        String dekId = null;
        if (request.memo() != null && !request.memo().isBlank()) {
            ciphertext = messageEncryptor.encrypt(request.memo().getBytes(StandardCharsets.UTF_8));
            dekId = messageEncryptor.dekId();
        }

        Checkin checkin = checkinRepository.save(Checkin.builder()
                .user(user)
                .timeOfDay(request.timeOfDay())
                .emotionType(request.emotionType())
                .conditionScore(request.conditionScore())
                .memoCiphertext(ciphertext)
                .memoDekId(dekId)
                .build());

        CheckinCreateResponse response = toCreateResponse(checkin, request.memo());

        final UUID checkinId = checkin.getId();
        final String emotionType = checkin.getEmotionType();
        final int conditionScore = checkin.getConditionScore();
        final String timeOfDay = checkin.getTimeOfDay();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (idempotencyKey != null) {
                        String cacheKey = idempotencyKey(idempotencyKey);
                        String cacheValue = serializeResponse(response);
                        redisTemplate.opsForValue().set(cacheKey, cacheValue, IDEMPOTENCY_TTL_SECONDS, TimeUnit.SECONDS);
                    }
                    aiResponseGenerator.generateAndSave(checkinId, emotionType, conditionScore, timeOfDay);
                }
            });
        } else {
            if (idempotencyKey != null) {
                String cacheKey = idempotencyKey(idempotencyKey);
                String cacheValue = serializeResponse(response);
                redisTemplate.opsForValue().set(cacheKey, cacheValue, IDEMPOTENCY_TTL_SECONDS, TimeUnit.SECONDS);
            }
            aiResponseGenerator.generateAndSave(checkinId, emotionType, conditionScore, timeOfDay);
        }

        return response;
    }

    @Transactional
    public CheckinUpdateResponse update(UUID userId, UUID checkinId, CheckinUpdateRequest request) {
        if (request.emotionType() == null && request.conditionScore() == null && request.memo() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (request.emotionType() != null) validateEmotion(request.emotionType());

        Checkin checkin = checkinRepository.findByIdAndUser_Id(checkinId, userId)
                .orElseThrow(() -> checkinRepository.existsById(checkinId)
                        ? new BusinessException(ErrorCode.CHECKIN_FORBIDDEN)
                        : new BusinessException(ErrorCode.CHECKIN_NOT_FOUND));

        LocalDate today = LocalDate.now(AppConstants.ZONE);
        if (!checkin.getCheckinDate().equals(today)) {
            throw new BusinessException(ErrorCode.CHECKIN_NOT_TODAY);
        }

        boolean updateMemo = request.memo() != null;
        byte[] ciphertext = null;
        String dekId = null;
        if (updateMemo && !request.memo().isBlank()) {
            ciphertext = messageEncryptor.encrypt(request.memo().getBytes(StandardCharsets.UTF_8));
            dekId = messageEncryptor.dekId();
        }

        checkin.update(request.emotionType(), request.conditionScore(), ciphertext, dekId, updateMemo);
        return toUpdateResponse(checkin, decryptMemo(checkin));
    }

    @Transactional(readOnly = true)
    public CheckinTodayResponse getToday(UUID userId) {
        LocalDate today = LocalDate.now(AppConstants.ZONE);
        List<Checkin> checkins = checkinRepository.findByUser_IdAndCheckinDate(userId, today);

        List<String> completedSlots = checkins.stream()
                .map(Checkin::getTimeOfDay)
                .toList();

        List<String> availableSlots = ALL_SLOTS.stream()
                .filter(slot -> !completedSlots.contains(slot))
                .toList();

        List<CheckinResponse> responses = checkins.stream()
                .map(c -> toResponse(c, decryptMemo(c)))
                .toList();

        return new CheckinTodayResponse(today, responses, completedSlots, availableSlots);
    }

    @Transactional(readOnly = true)
    public CheckinResponse getById(UUID userId, UUID checkinId) {
        Checkin checkin = checkinRepository.findByIdAndUser_Id(checkinId, userId)
                .orElseThrow(() -> checkinRepository.existsById(checkinId)
                        ? new BusinessException(ErrorCode.CHECKIN_FORBIDDEN)
                        : new BusinessException(ErrorCode.CHECKIN_NOT_FOUND));
        return toResponse(checkin, decryptMemo(checkin));
    }

    @Transactional(readOnly = true)
    public List<CheckinResponse> getHistory(UUID userId, String cursor, String month) {
        if (month != null) {
            YearMonth yearMonth;
            try {
                yearMonth = YearMonth.parse(month);
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            List<Checkin> checkins = checkinRepository.findByUser_IdAndCheckinDateBetween(
                    userId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
            return checkins.stream()
                    .map(c -> toResponse(c, decryptMemo(c)))
                    .toList();
        }

        PageRequest pageable = PageRequest.of(0, PAGE_SIZE);
        Slice<Checkin> slice;

        if (cursor != null) {
            OffsetDateTime cursorTime;
            try {
                cursorTime = OffsetDateTime.parse(
                        new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            slice = checkinRepository.findByUser_IdAndCreatedAtBefore(userId, cursorTime, pageable);
        } else {
            slice = checkinRepository.findByUser_IdOrderByCreatedAtDesc(userId, pageable);
        }

        return slice.getContent().stream()
                .map(c -> toResponse(c, decryptMemo(c)))
                .toList();
    }

    private CheckinCreateResponse toCreateResponse(Checkin checkin, String memo) {
        return new CheckinCreateResponse(
                checkin.getId(),
                checkin.getTimeOfDay(),
                checkin.getEmotionType(),
                checkin.getConditionScore(),
                memo,
                checkin.getAiResponse(),
                checkin.getCreatedAt()
        );
    }

    private CheckinUpdateResponse toUpdateResponse(Checkin checkin, String memo) {
        return new CheckinUpdateResponse(
                checkin.getId(),
                checkin.getTimeOfDay(),
                checkin.getEmotionType(),
                checkin.getConditionScore(),
                memo,
                checkin.getAiResponse(),
                checkin.getUpdatedAt()
        );
    }

    private CheckinResponse toResponse(Checkin checkin, String memo) {
        return new CheckinResponse(
                checkin.getId(),
                checkin.getTimeOfDay(),
                checkin.getEmotionType(),
                checkin.getConditionScore(),
                memo,
                checkin.getAiResponse(),
                checkin.getCreatedAt(),
                checkin.getUpdatedAt()
        );
    }

    private String decryptMemo(Checkin checkin) {
        if (checkin.getMemoCiphertext() == null) return null;
        try {
            return new String(messageEncryptor.decrypt(checkin.getMemoCiphertext()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("memo 복호화 실패 checkinId={}", checkin.getId(), e);
            return null;
        }
    }

    private void validateSlot(String timeOfDay) {
        if (!VALID_SLOTS.contains(timeOfDay)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateEmotion(String emotionType) {
        if (!VALID_EMOTIONS.contains(emotionType)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void checkRateLimit(UUID userId) {
        String key = "checkin:ratelimit:" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) return;
        if (count == 1) {
            redisTemplate.expire(key, RATE_LIMIT_TTL_SECONDS, TimeUnit.SECONDS);
        }
        if (count > RATE_LIMIT_MAX) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    private static String idempotencyKey(String key) {
        return "idempotency:" + key;
    }

    private String serializeResponse(CheckinCreateResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("idempotency 응답 직렬화 실패", e);
            return null;
        }
    }

    private CheckinCreateResponse deserializeResponse(String cached) {
        try {
            return objectMapper.readValue(cached, CheckinCreateResponse.class);
        } catch (Exception e) {
            log.warn("idempotency 캐시 역직렬화 실패 — 캐시 미스로 처리", e);
            return null;
        }
    }
}
