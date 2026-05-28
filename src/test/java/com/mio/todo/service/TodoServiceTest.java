package com.mio.todo.service;

import com.mio.checkin.domain.Checkin;
import com.mio.checkin.repository.CheckinRepository;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.mio.common.AppConstants;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private BehaviorTaskRepository behaviorTaskRepository;
    @Mock private CheckinRepository checkinRepository;
    @Mock private SessionRepository sessionRepository;

    private TodoService todoService;
    private UUID userId;
    private User completedUser;
    private User incompleteUser;

    @BeforeEach
    void setUp() {
        todoService = new TodoService(
                userRepository, behaviorTaskRepository,
                checkinRepository, sessionRepository,
                new TodoTemplateProvider()
        );
        userId = UUID.randomUUID();

        completedUser = User.builder()
                .socialProvider("kakao")
                .socialId("test-social-id")
                .privacyConsent(true)
                .build();
        completedUser.completeOnboarding("mio");

        incompleteUser = User.builder()
                .socialProvider("kakao")
                .socialId("test-social-id-2")
                .privacyConsent(true)
                .build();
    }

    @Test
    @DisplayName("온보딩 미완료 사용자는 generate 시 ONBOARDING_REQUIRED 예외를 발생시킨다")
    void generate_onboardingNotComplete_throwsForbidden() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(incompleteUser));

        assertThatThrownBy(() -> todoService.generate(
                userId, new TodoGenerateRequest("checkin", null)
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ONBOARDING_REQUIRED));
    }

    @Test
    @DisplayName("checkin source로 anxious 감정 전달 시 2개 태스크를 생성한다")
    void generate_checkinSource_createsTasks() {
        Checkin checkin = Checkin.builder()
                .user(completedUser)
                .timeOfDay("morning")
                .emotionType("anxious")
                .conditionScore(3)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(checkinRepository.findTopByUser_IdAndCheckinDateOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(Optional.of(checkin));
        when(behaviorTaskRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        List<TodoResponse> result = todoService.generate(userId, new TodoGenerateRequest("checkin", null));

        assertThat(result).hasSize(2);
        verify(behaviorTaskRepository).saveAll(argThat(tasks -> {
            List<BehaviorTask> list = (List<BehaviorTask>) tasks;
            return list.size() == 2;
        }));
    }

    @Test
    @DisplayName("오늘 날짜 필터링으로 getTodos 정상 동작한다")
    void getTodos_today_returnsFilteredList() {
        BehaviorTask task = buildTaskWithCreatedAt(OffsetDateTime.now(AppConstants.ZONE));

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
        BehaviorTask task = buildTaskWithCreatedAt(yesterday);

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
        BehaviorTask task = buildTaskWithCreatedAt(yesterday);

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(List.of(task));

        List<TodoResponse> result = todoService.getTodos(userId, LocalDate.now(AppConstants.ZONE).minusDays(1), "suggested");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("completed 처리 후 status와 completedAt이 변경된다")
    void checkin_complete_updatesStatus() {
        UUID todoId = UUID.randomUUID();
        BehaviorTask task = buildTaskWithCreatedAt(OffsetDateTime.now(AppConstants.ZONE));

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
                .generatedFrom("checkin")
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
                .generatedFrom("checkin")
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
    @DisplayName("지원하지 않는 source(pattern)는 INVALID_INPUT 예외를 발생시킨다")
    void generate_invalidSource_throwsInvalidInput() {
        assertThatThrownBy(() -> todoService.generate(
                userId, new TodoGenerateRequest("pattern", null)
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    @DisplayName("같은 source 컨텍스트의 suggested Todo가 있으면 중복 생성이 차단된다")
    void generate_duplicateSuggestedTodo_throwsConflict() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(checkinRepository.findTopByUser_IdAndCheckinDateOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(Optional.empty());
        when(behaviorTaskRepository
                .existsByUser_IdAndGeneratedFromAndSourceCheckinIsNullAndSourceSessionIsNullAndStatusAndCreatedAtBetween(
                        eq(userId), eq("checkin"), eq(TaskStatus.SUGGESTED), any(), any()
                ))
                .thenReturn(true);

        assertThatThrownBy(() -> todoService.generate(userId, new TodoGenerateRequest("checkin", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TODO_ALREADY_GENERATED));
    }

    @Test
    @DisplayName("중복 생성 유니크 제약 위반이면 TODO_ALREADY_GENERATED 예외로 변환한다")
    void generate_duplicateUniqueViolation_throwsConflict() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(checkinRepository.findTopByUser_IdAndCheckinDateOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(Optional.empty());
        when(behaviorTaskRepository.saveAll(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "save failed",
                        new RuntimeException("uq_behavior_tasks_suggested_default_per_day")
                ));

        assertThatThrownBy(() -> todoService.generate(userId, new TodoGenerateRequest("checkin", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TODO_ALREADY_GENERATED));
    }

    @Test
    @DisplayName("오늘 체크인이 없으면 default 템플릿 1개를 생성한다")
    void generate_noCheckin_usesDefaultTemplate() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(checkinRepository.findTopByUser_IdAndCheckinDateOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(Optional.empty());
        when(behaviorTaskRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        List<TodoResponse> result = todoService.generate(userId, new TodoGenerateRequest("checkin", null));

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("타 유저 체크인 sourceId로 generate 시 FORBIDDEN 예외를 발생시킨다")
    void generate_checkinSourceId_otherUser_throwsForbidden() {
        UUID otherCheckinId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(checkinRepository.findByIdAndUser_Id(otherCheckinId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.generate(
                userId, new TodoGenerateRequest("checkin", otherCheckinId)
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("타 유저 세션 sourceId로 generate 시 FORBIDDEN 예외를 발생시킨다")
    void generate_chatSourceId_otherUser_throwsForbidden() {
        UUID otherSessionId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(sessionRepository.findByIdAndUser_Id(otherSessionId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.generate(
                userId, new TodoGenerateRequest("chat", otherSessionId)
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("suggested 상태이고 어제 날짜인 Todo는 expired로 응답한다")
    void getTodos_expiredSuggestedTask_returnsExpiredStatus() {
        OffsetDateTime yesterday = OffsetDateTime.now(AppConstants.ZONE).minusDays(1);
        BehaviorTask task = buildTaskWithCreatedAt(yesterday);

        when(userRepository.findById(userId)).thenReturn(Optional.of(completedUser));
        when(behaviorTaskRepository.findByUser_IdAndCreatedAtBetween(eq(userId), any(), any()))
                .thenReturn(List.of(task));

        List<TodoResponse> result = todoService.getTodos(userId, LocalDate.now(AppConstants.ZONE).minusDays(1), null);

        assertThat(result.get(0).status()).isEqualTo("expired");
    }

    @Test
    @DisplayName("만료된 Todo 체크인 시 TODO_EXPIRED 예외를 발생시킨다")
    void checkin_expiredTask_throwsTodoExpired() {
        UUID todoId = UUID.randomUUID();
        BehaviorTask task = buildTaskWithCreatedAt(OffsetDateTime.now(AppConstants.ZONE).minusDays(1));

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

    private BehaviorTask buildTaskWithCreatedAt(OffsetDateTime createdAt) {
        try {
            BehaviorTask task = BehaviorTask.builder()
                    .user(completedUser)
                    .generatedFrom("checkin")
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
