package com.mio.dailytest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.dailytest.domain.DailyTest;
import com.mio.dailytest.domain.DailyTestResponse;
import com.mio.dailytest.dto.AnswerSubmitRequest;
import com.mio.dailytest.dto.DailyTestResultResponse;
import com.mio.dailytest.dto.DailyTestTodayResponse;
import com.mio.dailytest.repository.DailyTestRepository;
import com.mio.dailytest.repository.DailyTestResponseRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyTestServiceTest {

    @Mock private DailyTestRepository dailyTestRepository;
    @Mock private DailyTestResponseRepository dailyTestResponseRepository;
    @Mock private UserRepository userRepository;
    @Mock private DailyTestResultEngine resultEngine;

    private DailyTestService service;
    private UUID userId;
    private UUID testId;
    private User onboardedUser;
    private User pendingUser;

    private static final String CONTENT_JSON = """
            {
              "questions": [
                {
                  "id": "q1", "order": 1, "text": "질문1",
                  "options": [
                    {"id": "q1_a", "text": "옵션A", "score": 0, "tags": ["neutral"]},
                    {"id": "q1_b", "text": "옵션B", "score": 3, "tags": ["moderate"]}
                  ]
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        service = new DailyTestService(
                dailyTestRepository, dailyTestResponseRepository, userRepository,
                resultEngine, new ObjectMapper()
        );
        userId = UUID.randomUUID();
        testId = UUID.randomUUID();

        onboardedUser = User.builder().socialProvider("kakao").socialId("sid").privacyConsent(true).build();
        onboardedUser.completeOnboarding("mio");
        ReflectionTestUtils.setField(onboardedUser, "id", userId);

        pendingUser = User.builder().socialProvider("kakao").socialId("sid2").privacyConsent(true).build();
        ReflectionTestUtils.setField(pendingUser, "id", UUID.randomUUID());
    }

    @Test
    @DisplayName("getTodayTest - 온보딩 미완료 유저는 ONBOARDING_REQUIRED")
    void getTodayTest_notOnboarded_throwsOnboardingRequired() {
        when(userRepository.findById(any())).thenReturn(Optional.of(pendingUser));

        assertThatThrownBy(() -> service.getTodayTest(pendingUser.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ONBOARDING_REQUIRED);
    }

    @Test
    @DisplayName("getTodayTest - 오늘 테스트 없으면 DAILY_TEST_NOT_FOUND")
    void getTodayTest_noTodayTest_throwsNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findByActiveDate(LocalDate.now(AppConstants.ZONE))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTodayTest(userId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAILY_TEST_NOT_FOUND);
    }

    @Test
    @DisplayName("getTodayTest - 미완료 상태면 pending 응답 반환")
    void getTodayTest_notCompleted_returnsPending() {
        DailyTest test = buildDailyTest();
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findByActiveDate(LocalDate.now(AppConstants.ZONE))).thenReturn(Optional.of(test));
        when(dailyTestResponseRepository.findByUser_IdAndDailyTest_Id(userId, testId))
                .thenReturn(Optional.empty());

        DailyTestTodayResponse response = service.getTodayTest(userId);

        assertThat(response.completedToday()).isFalse();
        assertThat(response.testId()).isEqualTo(testId);
        assertThat(response.questions()).hasSize(1);
    }

    @Test
    @DisplayName("getTodayTest - 완료 상태면 completed 응답 반환")
    void getTodayTest_alreadyCompleted_returnsCompleted() {
        DailyTest test = buildDailyTest();
        DailyTestResponse existingResponse = buildDailyTestResponse(test, "안정된 하루");
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findByActiveDate(LocalDate.now(AppConstants.ZONE))).thenReturn(Optional.of(test));
        when(dailyTestResponseRepository.findByUser_IdAndDailyTest_Id(userId, testId))
                .thenReturn(Optional.of(existingResponse));
        // 재계산 결과는 저장된 summary와 다른 값 사용 → summary 출처가 DB임을 검증
        when(resultEngine.calculate(any(), any())).thenReturn(
                new DailyTestResultEngine.TestResult("재계산된_다른_요약", "설명", List.of("neutral"), "미오")
        );

        DailyTestTodayResponse response = service.getTodayTest(userId);

        assertThat(response.completedToday()).isTrue();
        assertThat(response.result().summary()).isEqualTo("안정된 하루"); // 저장된 값 사용
    }

    @Test
    @DisplayName("getTodayTest - content tags에 null 요소가 있으면 INVALID_DAILY_TEST_CONTENT")
    void getTodayTest_invalidTagElement_throwsInvalidDailyTestContent() {
        DailyTest test = buildDailyTest("""
                {
                  "questions": [
                    {
                      "id": "q1", "order": 1, "text": "질문1",
                      "options": [
                        {"id": "q1_a", "text": "옵션A", "score": 0, "tags": [null]}
                      ]
                    }
                  ]
                }
                """);
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findByActiveDate(LocalDate.now(AppConstants.ZONE))).thenReturn(Optional.of(test));

        assertThatThrownBy(() -> service.getTodayTest(userId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_DAILY_TEST_CONTENT);
    }

    @Test
    @DisplayName("submitAnswer - 오늘 날짜가 아닌 테스트는 DAILY_TEST_NOT_FOUND")
    void submitAnswer_notTodayTest_throwsNotFound() {
        DailyTest oldTest = buildDailyTest(LocalDate.now(AppConstants.ZONE).minusDays(1));
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findById(testId)).thenReturn(Optional.of(oldTest));

        AnswerSubmitRequest request = new AnswerSubmitRequest(Map.of("q1", "q1_a"));

        assertThatThrownBy(() -> service.submitAnswer(userId, testId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAILY_TEST_NOT_FOUND);
    }

    @Test
    @DisplayName("submitAnswer - 이미 완료한 경우 DAILY_TEST_ALREADY_COMPLETED")
    void submitAnswer_alreadyCompleted_throwsConflict() {
        DailyTest test = buildDailyTest();
        DailyTestResponse existingResponse = buildDailyTestResponse(test, "이미 완료");
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findById(testId)).thenReturn(Optional.of(test));
        when(dailyTestResponseRepository.findByUser_IdAndDailyTest_Id(userId, testId))
                .thenReturn(Optional.of(existingResponse));

        AnswerSubmitRequest request = new AnswerSubmitRequest(Map.of("q1", "q1_a"));

        assertThatThrownBy(() -> service.submitAnswer(userId, testId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAILY_TEST_ALREADY_COMPLETED);
    }

    @Test
    @DisplayName("submitAnswer - 정상 제출 시 결과 반환")
    void submitAnswer_valid_returnsResult() {
        DailyTest test = buildDailyTest();
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findById(testId)).thenReturn(Optional.of(test));
        when(dailyTestResponseRepository.findByUser_IdAndDailyTest_Id(userId, testId))
                .thenReturn(Optional.empty());
        when(resultEngine.calculate(any(), any())).thenReturn(
                new DailyTestResultEngine.TestResult("오늘은 비교적 안정된 하루였네요.", "설명", List.of("neutral"), "미오")
        );

        DailyTestResponse savedResponse = buildDailyTestResponse(test, "오늘은 비교적 안정된 하루였네요.");
        OffsetDateTime fixedAt = OffsetDateTime.of(2026, 5, 28, 12, 0, 0, 0, ZoneOffset.UTC);
        ReflectionTestUtils.setField(savedResponse, "createdAt", fixedAt);
        when(dailyTestResponseRepository.save(any())).thenReturn(savedResponse);

        AnswerSubmitRequest request = new AnswerSubmitRequest(Map.of("q1", "q1_a"));
        DailyTestResultResponse result = service.submitAnswer(userId, testId, request);

        assertThat(result.completedAt()).isEqualTo(fixedAt);
        assertThat(result.result().summary()).isEqualTo("오늘은 비교적 안정된 하루였네요.");
        assertThat(result.result().description()).isEqualTo("설명");
        assertThat(result.result().tags()).containsExactly("neutral");
        assertThat(result.result().characterComment()).isEqualTo("미오");
    }

    @Test
    @DisplayName("submitAnswer - unique 제약 위반이면 DAILY_TEST_ALREADY_COMPLETED")
    void submitAnswer_duplicateConstraint_throwsAlreadyCompleted() {
        DailyTest test = buildDailyTest();
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findById(testId)).thenReturn(Optional.of(test));
        when(dailyTestResponseRepository.findByUser_IdAndDailyTest_Id(userId, testId))
                .thenReturn(Optional.empty());
        when(resultEngine.calculate(any(), any())).thenReturn(
                new DailyTestResultEngine.TestResult("오늘은 비교적 안정된 하루였네요.", "설명", List.of("neutral"), "미오")
        );
        when(dailyTestResponseRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "save failed",
                        new RuntimeException("daily_test_responses_user_id_daily_test_id_key")
                ));

        AnswerSubmitRequest request = new AnswerSubmitRequest(Map.of("q1", "q1_a"));

        assertThatThrownBy(() -> service.submitAnswer(userId, testId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DAILY_TEST_ALREADY_COMPLETED);
    }

    @Test
    @DisplayName("submitAnswer - 다른 DB 무결성 예외는 그대로 전파")
    void submitAnswer_otherIntegrityViolation_propagates() {
        DailyTest test = buildDailyTest();
        when(userRepository.findById(userId)).thenReturn(Optional.of(onboardedUser));
        when(dailyTestRepository.findById(testId)).thenReturn(Optional.of(test));
        when(dailyTestResponseRepository.findByUser_IdAndDailyTest_Id(userId, testId))
                .thenReturn(Optional.empty());
        when(resultEngine.calculate(any(), any())).thenReturn(
                new DailyTestResultEngine.TestResult("오늘은 비교적 안정된 하루였네요.", "설명", List.of("neutral"), "미오")
        );
        when(dailyTestResponseRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "save failed",
                        new RuntimeException("other_constraint")
                ));

        AnswerSubmitRequest request = new AnswerSubmitRequest(Map.of("q1", "q1_a"));

        assertThatThrownBy(() -> service.submitAnswer(userId, testId, request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private DailyTest buildDailyTest() {
        return buildDailyTest(LocalDate.now(AppConstants.ZONE));
    }

    private DailyTest buildDailyTest(LocalDate date) {
        return buildDailyTest(date, CONTENT_JSON);
    }

    private DailyTest buildDailyTest(String content) {
        return buildDailyTest(LocalDate.now(AppConstants.ZONE), content);
    }

    private DailyTest buildDailyTest(LocalDate date, String content) {
        DailyTest test = DailyTest.builder()
                .title("오늘의 테스트")
                .description("설명")
                .content(content)
                .activeDate(date)
                .build();
        ReflectionTestUtils.setField(test, "id", testId);
        return test;
    }

    private DailyTestResponse buildDailyTestResponse(DailyTest test, String summary) {
        DailyTestResponse resp = DailyTestResponse.builder()
                .user(onboardedUser)
                .dailyTest(test)
                .answers("{}")
                .resultSummary(summary)
                .build();
        ReflectionTestUtils.setField(resp, "id", UUID.randomUUID());
        return resp;
    }
}
