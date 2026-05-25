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
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
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
        ensureNoSuggestedTodosForToday(userId, request.source(), checkin, session);

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

        try {
            return behaviorTaskRepository.saveAll(tasks).stream()
                    .map(TodoResponse::from)
                    .toList();
        } catch (DataIntegrityViolationException e) {
            if (isSuggestedTodoDuplicateViolation(e)) {
                throw new BusinessException(ErrorCode.TODO_ALREADY_GENERATED);
            }
            throw e;
        }
    }

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
        } else {
            task.skip();
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

    private void ensureNoSuggestedTodosForToday(UUID userId, String source, Checkin checkin, Session session) {
        LocalDate today = LocalDate.now(AppConstants.ZONE);
        OffsetDateTime from = today.atStartOfDay(AppConstants.ZONE).toOffsetDateTime();
        OffsetDateTime to = today.plusDays(1).atStartOfDay(AppConstants.ZONE).toOffsetDateTime();

        boolean exists;
        if (checkin != null) {
            exists = behaviorTaskRepository.existsByUser_IdAndGeneratedFromAndSourceCheckin_IdAndStatusAndCreatedAtBetween(
                    userId, source, checkin.getId(), TaskStatus.SUGGESTED, from, to
            );
        } else if (session != null) {
            exists = behaviorTaskRepository.existsByUser_IdAndGeneratedFromAndSourceSession_IdAndStatusAndCreatedAtBetween(
                    userId, source, session.getId(), TaskStatus.SUGGESTED, from, to
            );
        } else {
            exists = behaviorTaskRepository
                    .existsByUser_IdAndGeneratedFromAndSourceCheckinIsNullAndSourceSessionIsNullAndStatusAndCreatedAtBetween(
                            userId, source, TaskStatus.SUGGESTED, from, to
                    );
        }

        if (exists) {
            throw new BusinessException(ErrorCode.TODO_ALREADY_GENERATED);
        }
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

    private boolean isSuggestedTodoDuplicateViolation(DataIntegrityViolationException e) {
        Throwable mostSpecificCause = NestedExceptionUtils.getMostSpecificCause(e);
        return mostSpecificCause != null
                && mostSpecificCause.getMessage() != null
                && mostSpecificCause.getMessage().contains("uq_behavior_tasks_suggested_");
    }

    private record TaskWithEffectiveStatus(BehaviorTask task, TaskStatus status) {}
}
