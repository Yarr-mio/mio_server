package com.mio.todo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.todo.dto.TodoCheckinRequest;
import com.mio.todo.dto.TodoCheckinResponse;
import com.mio.todo.dto.TodoGenerateRequest;
import com.mio.todo.dto.TodoResponse;
import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.config.SecurityConfig;
import com.mio.todo.service.TodoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        value = TodoController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class TodoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private TodoService todoService;

    private static final UUID TEST_USER_ID = UUID.randomUUID();

    @Test
    @DisplayName("POST /v1/todos/generate - 성공 시 201 반환")
    void generate_success_returns201() throws Exception {
        List<TodoResponse> responses = List.of(
                new TodoResponse(UUID.randomUUID(), "5분 복식 호흡으로 긴장 풀기", "심리_안정", 1, 5, "suggested", OffsetDateTime.now(), "미오가 응원해요!"),
                new TodoResponse(UUID.randomUUID(), "걱정 목록 작성 후 통제 가능/불가능 분류하기", "인지_재구성", 2, 10, "suggested", OffsetDateTime.now(), "미오가 응원해요!")
        );
        when(todoService.generate(eq(TEST_USER_ID), any())).thenReturn(responses);

        mockMvc.perform(post("/v1/todos/generate")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoGenerateRequest("checkin", null)
                        )))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.todos").isArray())
                .andExpect(jsonPath("$.data.todos.length()").value(2));
    }

    @Test
    @DisplayName("POST /v1/todos/generate - 온보딩 미완료 시 403 반환")
    void generate_onboardingRequired_returns403() throws Exception {
        when(todoService.generate(eq(TEST_USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.ONBOARDING_REQUIRED));

        mockMvc.perform(post("/v1/todos/generate")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoGenerateRequest("checkin", null)
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"));
    }

    @Test
    @DisplayName("POST /v1/todos/generate - 지원하지 않는 source면 400 반환")
    void generate_invalidSource_returns400() throws Exception {
        mockMvc.perform(post("/v1/todos/generate")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoGenerateRequest("pattern", null)
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /v1/todos - 성공 시 200 반환")
    void getTodos_success_returns200() throws Exception {
        List<TodoResponse> responses = List.of(
                new TodoResponse(UUID.randomUUID(), "5분 복식 호흡으로 긴장 풀기", "심리_안정", 1, 5, "suggested", OffsetDateTime.now(), "미오가 응원해요!")
        );
        when(todoService.getTodos(eq(TEST_USER_ID), any(), any())).thenReturn(responses);

        mockMvc.perform(get("/v1/todos")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.todos").isArray())
                .andExpect(jsonPath("$.data.todos.length()").value(1));
    }

    @Test
    @DisplayName("POST /v1/todos/{todoId}/checkin - completed 처리 시 200 반환")
    void checkin_completed_returns200() throws Exception {
        UUID todoId = UUID.randomUUID();
        TodoCheckinResponse response = new TodoCheckinResponse("completed", 70, 40, "잘했어! 작은 것부터 하나씩 해나가는 게 진짜 대단한 거야 🎉");
        when(todoService.checkin(eq(TEST_USER_ID), eq(todoId), any())).thenReturn(response);

        mockMvc.perform(post("/v1/todos/{todoId}/checkin", todoId)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoCheckinRequest("completed", 70, 40, "괜찮았어요")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.before_emotion").value(70))
                .andExpect(jsonPath("$.data.after_emotion").value(40))
                .andExpect(jsonPath("$.data.character_reaction").isNotEmpty());
    }

    @Test
    @DisplayName("POST /v1/todos/{todoId}/checkin - 이미 완료된 Todo 재요청 시 409 반환")
    void checkin_alreadyCompleted_returns409() throws Exception {
        UUID todoId = UUID.randomUUID();
        when(todoService.checkin(eq(TEST_USER_ID), eq(todoId), any()))
                .thenThrow(new BusinessException(ErrorCode.TODO_ALREADY_COMPLETED));

        mockMvc.perform(post("/v1/todos/{todoId}/checkin", todoId)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoCheckinRequest("completed", 60, 30, "또 해봤어요")
                        )))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TODO_ALREADY_COMPLETED"));
    }

    @Test
    @DisplayName("POST /v1/todos/{todoId}/checkin - 만료된 Todo 처리 시 422 반환")
    void checkin_expired_returns422() throws Exception {
        UUID todoId = UUID.randomUUID();
        when(todoService.checkin(eq(TEST_USER_ID), eq(todoId), any()))
                .thenThrow(new BusinessException(ErrorCode.TODO_EXPIRED));

        mockMvc.perform(post("/v1/todos/{todoId}/checkin", todoId)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoCheckinRequest("completed", 60, 30, "늦었어요")
                        )))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TODO_EXPIRED"));
    }

    @Test
    @DisplayName("POST /v1/todos/{todoId}/checkin - 지원하지 않는 status면 400 반환")
    void checkin_invalidStatus_returns400() throws Exception {
        UUID todoId = UUID.randomUUID();

        mockMvc.perform(post("/v1/todos/{todoId}/checkin", todoId)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoCheckinRequest("done", 60, 30, "...")
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /v1/todos/{todoId}/checkin - 다른 유저 Todo 접근 시 403 반환")
    void checkin_forbidden_returns403() throws Exception {
        UUID todoId = UUID.randomUUID();
        when(todoService.checkin(eq(TEST_USER_ID), eq(todoId), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(post("/v1/todos/{todoId}/checkin", todoId)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoCheckinRequest("completed", 70, 40, "...")
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("POST /v1/todos/generate - principal 없으면 401 반환")
    void generate_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/v1/todos/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TodoGenerateRequest("checkin", null)
                        )))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /v1/todos - principal 없으면 401 반환")
    void getTodos_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/v1/todos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
