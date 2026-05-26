package com.mio.dailytest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.dailytest.domain.DailyTest;
import com.mio.dailytest.domain.DailyTestResponse;
import com.mio.dailytest.dto.*;
import com.mio.dailytest.repository.DailyTestRepository;
import com.mio.dailytest.repository.DailyTestResponseRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DailyTestService {

    private static final String DUPLICATE_RESPONSE_CONSTRAINT =
            "daily_test_responses_user_id_daily_test_id_key";

    private final DailyTestRepository dailyTestRepository;
    private final DailyTestResponseRepository dailyTestResponseRepository;
    private final UserRepository userRepository;
    private final DailyTestResultEngine resultEngine;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DailyTestTodayResponse getTodayTest(UUID userId) {
        User user = findUser(userId);
        checkOnboarding(user);

        DailyTest test = dailyTestRepository.findByActiveDate(LocalDate.now(AppConstants.ZONE))
                .orElseThrow(() -> new BusinessException(ErrorCode.DAILY_TEST_NOT_FOUND));

        return dailyTestResponseRepository.findByUser_IdAndDailyTest_Id(userId, test.getId())
                .map(resp -> DailyTestTodayResponse.completed(test.getId(), resp.getResultSummary()))
                .orElseGet(() -> DailyTestTodayResponse.pending(
                        test.getId(),
                        test.getTitle(),
                        test.getDescription(),
                        mapToQuestionDtos(parseContent(test.getContent()))
                ));
    }

    @Transactional
    public DailyTestResultResponse submitAnswer(UUID userId, UUID testId, AnswerSubmitRequest request) {
        User user = findUser(userId);
        checkOnboarding(user);

        DailyTest test = dailyTestRepository.findById(testId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DAILY_TEST_NOT_FOUND));

        if (!LocalDate.now(AppConstants.ZONE).equals(test.getActiveDate())) {
            throw new BusinessException(ErrorCode.DAILY_TEST_NOT_FOUND);
        }

        if (dailyTestResponseRepository.findByUser_IdAndDailyTest_Id(userId, testId).isPresent()) {
            throw new BusinessException(ErrorCode.DAILY_TEST_ALREADY_COMPLETED);
        }

        DailyTestContent content = parseContent(test.getContent());
        String resultSummary = resultEngine.calculate(content, request.answers());
        String answersJson = serializeAnswers(request.answers());

        DailyTestResponse response = DailyTestResponse.builder()
                .user(user)
                .dailyTest(test)
                .answers(answersJson)
                .resultSummary(resultSummary)
                .build();

        try {
            DailyTestResponse saved = dailyTestResponseRepository.save(response);
            return new DailyTestResultResponse(saved.getId(), testId, resultSummary);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateResponseViolation(e)) {
                throw new BusinessException(ErrorCode.DAILY_TEST_ALREADY_COMPLETED);
            }
            throw e;
        }
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private void checkOnboarding(User user) {
        String step = user.getSignupStep();
        if (!"ONBOARDING_COMPLETED".equals(step) && !"COMPLETED".equals(step)) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }
    }

    private DailyTestContent parseContent(String contentJson) {
        try {
            return objectMapper.readValue(contentJson, DailyTestContent.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_DAILY_TEST_CONTENT);
        }
    }

    private String serializeAnswers(Object answers) {
        try {
            return objectMapper.writeValueAsString(answers);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean isDuplicateResponseViolation(DataIntegrityViolationException e) {
        Throwable mostSpecificCause = NestedExceptionUtils.getMostSpecificCause(e);
        return mostSpecificCause != null
                && mostSpecificCause.getMessage() != null
                && mostSpecificCause.getMessage().contains(DUPLICATE_RESPONSE_CONSTRAINT);
    }

    private List<DailyTestTodayResponse.QuestionDto> mapToQuestionDtos(DailyTestContent content) {
        return content.questions().stream()
                .sorted(Comparator.comparingInt(DailyTestContent.Question::order))
                .map(q -> new DailyTestTodayResponse.QuestionDto(
                        q.id(),
                        q.order(),
                        q.text(),
                        q.options().stream()
                                .map(o -> new DailyTestTodayResponse.OptionDto(o.id(), o.text()))
                                .toList()
                ))
                .toList();
    }
}
