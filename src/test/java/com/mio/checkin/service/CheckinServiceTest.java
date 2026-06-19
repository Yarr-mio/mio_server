package com.mio.checkin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mio.checkin.domain.Checkin;
import com.mio.checkin.dto.*;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.common.AppConstants;
import com.mio.common.crypto.StubMessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.user.domain.SignupStep;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckinServiceTest {

    @Mock private CheckinRepository checkinRepository;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private CheckinAiResponseGenerator aiResponseGenerator;

    private CheckinService checkinService;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        checkinService = new CheckinService(
                checkinRepository, userRepository, new StubMessageEncryptor(), redisTemplate, objectMapper,
                aiResponseGenerator);
        userId = UUID.randomUUID();
        user = User.builder()
                .socialProvider("kakao")
                .socialId("kakao-001")
                .privacyConsent(true)
                .signupStep(SignupStep.ONBOARDING_COMPLETED)
                .build();
    }

    @Nested
    @DisplayName("POST /v1/checkins — 체크인 등록")
    class Submit {

        @Test
        @DisplayName("정상 등록 시 응답 데이터 반환")
        void submit_success() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(1L);
            when(checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(any(), any(), any())).thenReturn(false);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(checkinRepository.save(any())).thenReturn(checkin("morning", "anxious", 3));

            CheckinCreateResponse result = checkinService.submit(userId,
                    new CheckinRequest("morning", "anxious", 3, "메모"), null);

            assertThat(result.timeOfDay()).isEqualTo("morning");
            assertThat(result.emotionType()).isEqualTo("anxious");
            assertThat(result.conditionScore()).isEqualTo(3);
            assertThat(result.memo()).isEqualTo("메모");
        }

        @Test
        @DisplayName("동일 슬롯 중복 체크인 시 ALREADY_CHECKED_IN")
        void submit_duplicateSlot_throws() {
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenReturn(null);
            when(checkinRepository.existsByUser_IdAndCheckinDateAndTimeOfDay(any(), any(), eq("morning"))).thenReturn(true);

            assertThatThrownBy(() -> checkinService.submit(userId,
                    new CheckinRequest("morning", "anxious", 3, null), "idem-key"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.ALREADY_CHECKED_IN));
        }

        @Test
        @DisplayName("유효하지 않은 emotion_type 시 INVALID_INPUT")
        void submit_invalidEmotion_throws() {
            assertThatThrownBy(() -> checkinService.submit(userId,
                    new CheckinRequest("morning", "unknown", 3, null), null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_INPUT));
        }

        @Test
        @DisplayName("Idempotency-Key 캐시 히트 시 저장 없이 캐시 응답 반환")
        void submit_idempotencyHit_returnsCached() throws Exception {
            UUID checkinId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            CheckinCreateResponse cached = new CheckinCreateResponse(checkinId, "morning", "calm", 4, null, null, now);
            String cachedJson = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writeValueAsString(cached);

            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(anyString())).thenReturn(1L);
            when(valueOps.get("idempotency:idem-001")).thenReturn(cachedJson);

            CheckinCreateResponse result = checkinService.submit(userId,
                    new CheckinRequest("morning", "calm", 4, null), "idem-001");

            assertThat(result.checkinId()).isEqualTo(checkinId);
            verify(checkinRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("PUT /v1/checkins/{id} — 체크인 수정")
    class Update {

        @Test
        @DisplayName("당일 체크인 정상 수정")
        void update_success() {
            Checkin c = checkinToday("morning", "anxious", 2);
            when(checkinRepository.findByIdAndUser_Id(any(), eq(userId))).thenReturn(Optional.of(c));

            CheckinUpdateResponse result = checkinService.update(userId, UUID.randomUUID(),
                    new CheckinUpdateRequest("calm", 4, "나아졌어"));

            assertThat(result.emotionType()).isEqualTo("calm");
            assertThat(result.conditionScore()).isEqualTo(4);
            assertThat(result.memo()).isEqualTo("나아졌어");
        }

        @Test
        @DisplayName("memo 미전달(null) 시 기존 메모 응답에 포함")
        void update_memoNotProvided_returnsExistingMemo() {
            Checkin c = checkinToday("morning", "anxious", 2);
            // StubMessageEncryptor: encrypt/decrypt가 평문 그대로 반환
            byte[] existing = "기존메모".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            setField(c, "memoCiphertext", existing);
            when(checkinRepository.findByIdAndUser_Id(any(), eq(userId))).thenReturn(Optional.of(c));

            CheckinUpdateResponse result = checkinService.update(userId, UUID.randomUUID(),
                    new CheckinUpdateRequest("calm", null, null));

            assertThat(result.emotionType()).isEqualTo("calm");
            assertThat(result.memo()).isEqualTo("기존메모");
        }

        @Test
        @DisplayName("memo 빈 문자열 전달 시 메모 삭제")
        void update_clearMemo() {
            Checkin c = checkinToday("morning", "anxious", 2);
            setField(c, "memoCiphertext", new byte[]{1, 2, 3});
            when(checkinRepository.findByIdAndUser_Id(any(), eq(userId))).thenReturn(Optional.of(c));

            CheckinUpdateResponse result = checkinService.update(userId, UUID.randomUUID(),
                    new CheckinUpdateRequest(null, null, ""));

            assertThat(result.memo()).isNull();
            assertThat(c.getMemoCiphertext()).isNull();
        }

        @Test
        @DisplayName("존재하지 않는 체크인 시 CHECKIN_NOT_FOUND")
        void update_notFound_throws() {
            when(checkinRepository.findByIdAndUser_Id(any(), eq(userId))).thenReturn(Optional.empty());
            when(checkinRepository.existsById(any())).thenReturn(false);

            assertThatThrownBy(() -> checkinService.update(userId, UUID.randomUUID(),
                    new CheckinUpdateRequest("calm", 3, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CHECKIN_NOT_FOUND));
        }

        @Test
        @DisplayName("타인의 체크인 접근 시 CHECKIN_FORBIDDEN")
        void update_forbidden_throws() {
            when(checkinRepository.findByIdAndUser_Id(any(), eq(userId))).thenReturn(Optional.empty());
            when(checkinRepository.existsById(any())).thenReturn(true);

            assertThatThrownBy(() -> checkinService.update(userId, UUID.randomUUID(),
                    new CheckinUpdateRequest(null, null, "수정")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CHECKIN_FORBIDDEN));
        }

        @Test
        @DisplayName("익일 이후 수정 시도 시 CHECKIN_NOT_TODAY")
        void update_notToday_throws() {
            Checkin c = checkinYesterday("morning", "anxious", 2);
            when(checkinRepository.findByIdAndUser_Id(any(), eq(userId))).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> checkinService.update(userId, UUID.randomUUID(),
                    new CheckinUpdateRequest(null, null, "수정")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CHECKIN_NOT_TODAY));
        }
    }

    @Nested
    @DisplayName("GET /v1/checkins/today — 오늘 현황")
    class GetToday {

        @Test
        @DisplayName("morning 완료 시 completed/available 슬롯 분리")
        void getToday_slotsPartitioned() {
            LocalDate today = LocalDate.now(AppConstants.ZONE);
            when(checkinRepository.findByUser_IdAndCheckinDate(userId, today))
                    .thenReturn(List.of(checkinToday("morning", "calm", 3)));

            CheckinTodayResponse result = checkinService.getToday(userId);

            assertThat(result.completedSlots()).containsExactly("morning");
            assertThat(result.availableSlots()).containsExactlyInAnyOrder("afternoon", "evening");
        }

        @Test
        @DisplayName("체크인 없으면 available_slots 3개 전부 반환")
        void getToday_noCheckin_allAvailable() {
            when(checkinRepository.findByUser_IdAndCheckinDate(any(), any())).thenReturn(List.of());

            CheckinTodayResponse result = checkinService.getToday(userId);

            assertThat(result.checkins()).isEmpty();
            assertThat(result.availableSlots()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("GET /v1/checkins — 이력 조회")
    class GetHistory {

        @Test
        @DisplayName("커서 없이 최신순 반환")
        void getHistory_noCursor() {
            when(checkinRepository.findByUser_IdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(checkinToday("evening", "calm", 3))));

            List<CheckinResponse> result = checkinService.getHistory(userId, null, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).timeOfDay()).isEqualTo("evening");
        }
    }

    // ── 헬퍼 ──

    private Checkin checkin(String slot, String emotion, int score) {
        return Checkin.builder()
                .user(user)
                .timeOfDay(slot)
                .emotionType(emotion)
                .conditionScore(score)
                .build();
    }

    private Checkin checkinToday(String slot, String emotion, int score) {
        Checkin c = checkin(slot, emotion, score);
        setField(c, "checkinDate", LocalDate.now(AppConstants.ZONE));
        return c;
    }

    private Checkin checkinYesterday(String slot, String emotion, int score) {
        Checkin c = checkin(slot, emotion, score);
        setField(c, "checkinDate", LocalDate.now(AppConstants.ZONE).minusDays(1));
        return c;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
