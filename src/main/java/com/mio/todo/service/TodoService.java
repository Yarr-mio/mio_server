package com.mio.todo.service;

import com.mio.common.AppConstants;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;
import com.mio.todo.dto.TodoCheckinRequest;
import com.mio.todo.dto.TodoCheckinResponse;
import com.mio.todo.dto.TodoResponse;
import com.mio.todo.event.TodoCompletedEvent;
import com.mio.todo.event.TodoPartialCompletedEvent;
import com.mio.todo.event.TodoSkippedEvent;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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

    private static final Set<String> VALID_CHECKIN_STATUSES = Set.of("completed", "partial_completed", "skipped");

    private final UserRepository userRepository;
    private final BehaviorTaskRepository behaviorTaskRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<TodoResponse> getTodos(UUID userId, LocalDate date, String status) {
        User user = findUser(userId);
        requireOnboardingComplete(user);

        LocalDate targetDate = date != null ? date : LocalDate.now(AppConstants.ZONE);
        OffsetDateTime from = targetDate.atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime to = targetDate.plusDays(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        LocalDate today = LocalDate.now(AppConstants.ZONE);
        TaskStatus requestedStatus = parseStatus(status);

        return behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(userId, from, to).stream()
                .map(task -> new TaskWithEffectiveStatus(task, effectiveStatus(task, today)))
                .filter(task -> requestedStatus == null || task.status() == requestedStatus)
                .map(task -> TodoResponse.fromWithStatus(task.task(), task.status()))
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
            throw new BusinessException(ErrorCode.TODO_EXPIRED);
        }

        if ("completed".equals(request.status())) {
            task.complete(request.beforeEmotion(), request.afterEmotion(), request.feedback());
            publishCompletedEvent(userId, task, request);
        } else if ("partial_completed".equals(request.status())) {
            task.partialComplete(request.beforeEmotion(), request.afterEmotion(), request.feedback());
            publishPartialCompletedEvent(userId, task, request);
        } else {
            task.skip();
            publishSkippedEvent(userId, task);
        }

        return TodoCheckinResponse.from(task);
    }

    private boolean isExpired(BehaviorTask task, LocalDate today) {
        return TaskStatus.SUGGESTED == task.getStatus()
                && task.getCreatedAt().toLocalDate().isBefore(today);
    }

    private TaskStatus effectiveStatus(BehaviorTask task, LocalDate today) {
        return isExpired(task, today) ? TaskStatus.EXPIRED : task.getStatus();
    }

    private TaskStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return TaskStatus.fromValue(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private void requireOnboardingComplete(User user) {
        if (!user.getSignupStep().isOnboardingComplete()) {
            throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
        }
    }

    private void publishCompletedEvent(UUID userId, BehaviorTask task, TodoCheckinRequest request) {
        UUID sessionId = task.getSourceSession() != null
                ? task.getSourceSession().getId() : null;
        eventPublisher.publishEvent(new TodoCompletedEvent(
                userId,
                task.getId(),
                sessionId,
                task.getInterventionKind(),
                request.beforeEmotion(),
                request.afterEmotion(),
                task.getCharacterId()
        ));
    }

    private void publishPartialCompletedEvent(UUID userId, BehaviorTask task, TodoCheckinRequest request) {
        UUID sessionId = task.getSourceSession() != null
                ? task.getSourceSession().getId() : null;
        eventPublisher.publishEvent(new TodoPartialCompletedEvent(
                userId,
                task.getId(),
                sessionId,
                task.getInterventionKind(),
                request.beforeEmotion(),
                request.afterEmotion(),
                task.getCharacterId()
        ));
    }

    private void publishSkippedEvent(UUID userId, BehaviorTask task) {
        UUID sessionId = task.getSourceSession() != null
                ? task.getSourceSession().getId() : null;
        eventPublisher.publishEvent(new TodoSkippedEvent(
                userId,
                task.getId(),
                sessionId,
                task.getInterventionKind()
        ));
    }

    private record TaskWithEffectiveStatus(BehaviorTask task, TaskStatus status) {}
}
