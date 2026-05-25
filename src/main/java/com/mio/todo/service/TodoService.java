package com.mio.todo.service;

import com.mio.checkin.domain.Checkin;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionStatus;
import com.mio.session.repository.SessionRepository;
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;
import com.mio.todo.dto.TodoCheckinRequest;
import com.mio.todo.dto.TodoCheckinResponse;
import com.mio.todo.dto.TodoGenerateRequest;
import com.mio.todo.dto.TodoResponse;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TodoService {

    private static final Set<String> ONBOARDING_COMPLETE_STEPS = Set.of("ONBOARDING_COMPLETED", "COMPLETED");
    private static final Set<String> VALID_SOURCES = Set.of("checkin", "chat");
    private static final Set<String> VALID_CHECKIN_STATUSES = Set.of("completed", "skipped");

    private final UserRepository userRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final CheckinRepository checkinRepository;
    private final SessionRepository sessionRepository;
    private final TodoTemplateProvider templateProvider;

    @Transactional
    public List<TodoResponse> generate(UUID userId, TodoGenerateRequest request) {
        if (!VALID_SOURCES.contains(request.source())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        User user = findUser(userId);
        requireOnboardingComplete(user);

        Checkin checkin = resolveCheckin(userId, request);
        String emotionType = checkin != null ? checkin.getEmotionType() : "default";
        List<TodoTemplateProvider.TaskTemplate> templates = templateProvider.getTemplates(emotionType);
        Session session = resolveSession(userId, request);

        List<BehaviorTask> tasks = templates.stream()
                .map(t -> BehaviorTask.builder()
                        .user(user)
                        .generatedFrom(request.source())
                        .actionText(t.actionText())
                        .category(t.category())
                        .difficulty(t.difficulty())
                        .estimatedMinutes(t.estimatedMinutes())
                        .sourceCheckin(checkin)
                        .sourceSession(session)
                        .build())
                .toList();

        return behaviorTaskRepository.saveAll(tasks).stream()
                .map(TodoResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TodoResponse> getTodos(UUID userId, LocalDate date, String status) {
        User user = findUser(userId);
        requireOnboardingComplete(user);

        LocalDate targetDate = date != null ? date : LocalDate.now(AppConstants.ZONE);
        OffsetDateTime from = targetDate.atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime to = targetDate.plusDays(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        LocalDate today = LocalDate.now(AppConstants.ZONE);

        List<BehaviorTask> tasks;
        if (status != null) {
            TaskStatus taskStatus;
            try {
                taskStatus = TaskStatus.fromValue(status);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            tasks = behaviorTaskRepository.findByUser_IdAndStatusAndCreatedAtBetween(userId, taskStatus, from, to);
        } else {
            tasks = behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(userId, from, to);
        }

        return tasks.stream()
                .map(t -> isExpired(t, today) ? TodoResponse.fromWithStatus(t, "expired") : TodoResponse.from(t))
                .toList();
    }

    @Transactional
    public TodoCheckinResponse checkin(UUID userId, UUID todoId, TodoCheckinRequest request) {
        if (!VALID_CHECKIN_STATUSES.contains(request.status())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        BehaviorTask task = behaviorTaskRepository.findById(todoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TODO_NOT_FOUND));

        if (!task.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        LocalDate today = LocalDate.now(AppConstants.ZONE);
        if (isExpired(task, today)) {
            throw new BusinessException(ErrorCode.TODO_ALREADY_COMPLETED);
        }

        if ("completed".equals(request.status())) {
            task.complete(request.beforeEmotion(), request.afterEmotion(), request.feedback());
        } else {
            task.skip();
        }

        return TodoCheckinResponse.from(task);
    }

    private boolean isExpired(BehaviorTask task, LocalDate today) {
        return TaskStatus.SUGGESTED == task.getStatus()
                && task.getCreatedAt().toLocalDate().isBefore(today);
    }

    private Checkin resolveCheckin(UUID userId, TodoGenerateRequest request) {
        if (!"checkin".equals(request.source())) {
            return null;
        }
        if (request.sourceId() != null) {
            return checkinRepository.findByIdAndUser_Id(request.sourceId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        }
        return checkinRepository
                .findTopByUser_IdAndCheckinDateOrderByCreatedAtDesc(userId, LocalDate.now(AppConstants.ZONE))
                .orElse(null);
    }

    private Session resolveSession(UUID userId, TodoGenerateRequest request) {
        if (!"chat".equals(request.source())) {
            return null;
        }
        if (request.sourceId() != null) {
            return sessionRepository.findByIdAndUser_Id(request.sourceId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        }
        return sessionRepository.findByUser_IdAndStatus(userId, SessionStatus.ACTIVE).orElse(null);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private void requireOnboardingComplete(User user) {
        if (!ONBOARDING_COMPLETE_STEPS.contains(user.getSignupStep())) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }
    }
}
