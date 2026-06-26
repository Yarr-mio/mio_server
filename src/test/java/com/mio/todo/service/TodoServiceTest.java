package com.mio.todo.service;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.todo.domain.BehaviorTask;
import com.mio.todo.domain.TaskStatus;
import com.mio.todo.dto.TodoCheckinRequest;
import com.mio.todo.dto.TodoCheckinResponse;
import com.mio.todo.dto.TodoResponse;
import com.mio.todo.repository.BehaviorTaskRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mio.common.AppConstants;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private BehaviorTaskRepository behaviorTaskRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private TodoService todoService;
    private UUID userId;
    private User completedUser;

    @BeforeEach
    void setUp() {
        todoService = new TodoService(
                userRepository, behaviorTaskRepository,
                eventPublisher
        );
        userId = UUID.randomUUID();

        completedUser = User.builder()
                .socialProvider("kakao")
                .socialId("test-social-id")
                .privacyConsent(true)
                .build();
        completedUser.completeOnboarding("mio");
    }

    @Test
    @DisplayName("오늘 날짜 필터링으로 getTodos 정상 동작한다")
    void getTodos_today_returnsFilteredList() {
        BehaviorTask task = buildTask(OffsetDateTime.now(AppConstants.ZONE));

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(List.of(task));

        List<TodoResponse> result = todoService.getTodos(userId, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).actionText()).isEqualTo("5분 복식 호흡");
    }

    @Test
    @DisplayName("status=expired 조회 시 과거 suggested Todo를 반환한다")
    void getTodos_statusExpired_returnsExpiredTasks() {
        OffsetDateTime yesterday = OffsetDateTime.now(AppConstants.ZONE).minusDays(1);
        BehaviorTask task = buildTask(yesterday);

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(List.of(task));

        List<TodoResponse> result = todoService.getTodos(userId, LocalDate.now(AppConstants.ZONE).minusDays(1), "expired");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("expired");
    }

    @Test
    @DisplayName("status=suggested 조회 시 과거 suggested Todo는 제외된다")
    void getTodos_statusSuggested_excludesExpiredTasks() {
        OffsetDateTime yesterday = OffsetDateTime.now(AppConstants.ZONE).minusDays(1);
        BehaviorTask task = buildTask(yesterday);

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(List.of(task));

        List<TodoResponse> result = todoService.getTodos(userId, LocalDate.now(AppConstants.ZONE).minusDays(1), "suggested");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("suggested 상태이고 어제 날짜인 Todo는 expired로 응답한다")
    void getTodos_expiredSuggestedTask_returnsExpiredStatus() {
        OffsetDateTime yesterday = OffsetDateTime.now(AppConstants.ZONE).minusDays(1);
        BehaviorTask task = buildTask(yesterday);

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(List.of(task));

        List<TodoResponse> result = todoService.getTodos(userId, LocalDate.now(AppConstants.ZONE).minusDays(1), null);

        assertThat(result.get(0).status()).isEqualTo("expired");
    }

    @Test
    @DisplayName("completed 처리 후 status와 completedAt이 변경된다")
    void checkin_complete_updatesStatus() {
        UUID todoId = UUID.randomUUID();
        BehaviorTask task = buildTask(OffsetDateTime.now(AppConstants.ZONE));

        setUserId(completedUser, userId);
        when(behaviorTaskRepository.findById(todoId)).thenReturn(Optional.of(task));

        TodoCheckinResponse response = todoService.checkin(
                userId, todoId,
                new TodoCheckinRequest("completed", 70, 40, "괜찮았어요")
        );

        assertThat(response.status()).isEqualTo("completed");
        assertThat(response.beforeEmotion()).isEqualTo(70);
        assertThat(response.afterEmotion()).isEqualTo(40);
        assertThat(response.characterReaction()).isNotBlank();
    }

    @Test
    @DisplayName("이미 completed인 Todo에 재요청 시 TODO_ALREADY_COMPLETED 예외를 발생시킨다")
    void checkin_alreadyCompleted_throwsConflict() {
        UUID todoId = UUID.randomUUID();
        BehaviorTask task = BehaviorTask.builder()
                .user(completedUser)
                .generatedFrom("chat")
                .actionText("5분 복식 호흡")
                .category("심리_안정")
                .difficulty(1)
                .estimatedMinutes(5)
                .build();

        setUserId(completedUser, userId);
        task.complete(70, 40, "괜찮았어요");

        when(behaviorTaskRepository.findById(todoId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> todoService.checkin(
                userId, todoId,
                new TodoCheckinRequest("completed", 60, 30, "또 해봤어요")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TODO_ALREADY_COMPLETED));
    }

    @Test
    @DisplayName("다른 유저의 Todo 접근 시 FORBIDDEN 예외를 발생시킨다")
    void checkin_otherUserTodo_throwsForbidden() {
        UUID todoId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        User otherUser = User.builder()
                .socialProvider("kakao")
                .socialId("other-social-id")
                .privacyConsent(true)
                .build();
        otherUser.completeOnboarding("mio");
        setUserId(otherUser, otherUserId);

        BehaviorTask task = BehaviorTask.builder()
                .user(otherUser)
                .generatedFrom("chat")
                .actionText("5분 복식 호흡")
                .category("심리_안정")
                .difficulty(1)
                .estimatedMinutes(5)
                .build();

        when(behaviorTaskRepository.findById(todoId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> todoService.checkin(
                userId, todoId,
                new TodoCheckinRequest("completed", 70, 40, "...")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("만료된 Todo 체크인 시 TODO_EXPIRED 예외를 발생시킨다")
    void checkin_expiredTask_throwsTodoExpired() {
        UUID todoId = UUID.randomUUID();
        BehaviorTask task = buildTask(OffsetDateTime.now(AppConstants.ZONE).minusDays(1));

        setUserId(completedUser, userId);
        when(behaviorTaskRepository.findById(todoId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> todoService.checkin(
                userId, todoId,
                new TodoCheckinRequest("completed", 70, 40, "늦었어요")
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TODO_EXPIRED));
    }

    private void setUserId(User user, UUID id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BehaviorTask buildTask(OffsetDateTime createdAt) {
        try {
            BehaviorTask task = BehaviorTask.builder()
                    .user(completedUser)
                    .generatedFrom("chat")
                    .actionText("5분 복식 호흡")
                    .category("심리_안정")
                    .difficulty(1)
                    .estimatedMinutes(5)
                    .build();
            var field = BehaviorTask.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(task, createdAt);
            return task;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
