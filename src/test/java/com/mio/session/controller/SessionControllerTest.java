package com.mio.session.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.common.error.GlobalExceptionHandler;
import com.mio.session.dto.*;
import com.mio.auth.filter.JwtAuthenticationFilter;
import com.mio.config.SecurityConfig;
import com.mio.session.service.SessionMessageHistoryService;
import com.mio.session.service.SessionService;
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
import org.mockito.InOrder;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        value = SessionController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SecurityConfig.class)
        }
)
@Import(GlobalExceptionHandler.class)
class SessionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private SessionService sessionService;
    @MockBean private SessionMessageHistoryService sessionMessageHistoryService;

    private static final UUID TEST_USER_ID = UUID.randomUUID();
    private static final UUID TEST_SESSION_ID = UUID.randomUUID();

    /** 선제 인사 응답 픽스처 (이슈 #530). */
    private static final InitialAssistantMessageResponse OPENING = new InitialAssistantMessageResponse(
            UUID.randomUUID(), "assistant", "session_opening",
            "안녕! 난 미오야 🐧 오늘 어떤 하루를 보냈어?",
            OffsetDateTime.now());

    @Test
    @DisplayName("GET /v1/sessions/{id}/messages - 대화 이력을 오래된 순으로 반환")
    void getSessionMessages_returnsHistory() throws Exception {
        var opening = new SessionMessagesResponse.SessionMessageItem(
                UUID.randomUUID(), "assistant", "session_opening",
                "안녕! 난 미오야 🐧 오늘 어떤 하루를 보냈어?", OffsetDateTime.now());
        var userTurn = new SessionMessagesResponse.SessionMessageItem(
                UUID.randomUUID(), "user", "conversation", "오늘 발표가 있었어", OffsetDateTime.now());
        when(sessionMessageHistoryService.getHistory(eq(TEST_USER_ID), eq(TEST_SESSION_ID), any(), any()))
                .thenReturn(new SessionMessagesResponse(
                        TEST_SESSION_ID, List.of(opening, userTurn), "next-cursor", true));

        mockMvc.perform(get("/v1/sessions/{sessionId}/messages", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session_id").value(TEST_SESSION_ID.toString()))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].kind").value("session_opening"))
                .andExpect(jsonPath("$.data.messages[1].role").value("user"))
                .andExpect(jsonPath("$.data.next_cursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.has_next").value(true));
    }

    @Test
    @DisplayName("GET /v1/sessions/{id}/messages - cursor·limit 쿼리 파라미터를 서비스로 전달")
    void getSessionMessages_passesQueryParams() throws Exception {
        when(sessionMessageHistoryService.getHistory(any(), any(), any(), any()))
                .thenReturn(new SessionMessagesResponse(TEST_SESSION_ID, List.of(), null, false));

        mockMvc.perform(get("/v1/sessions/{sessionId}/messages", TEST_SESSION_ID)
                        .param("cursor", "abc123")
                        .param("limit", "20")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isArray())
                .andExpect(jsonPath("$.data.next_cursor").doesNotExist());

        verify(sessionMessageHistoryService).getHistory(TEST_USER_ID, TEST_SESSION_ID, "abc123", 20);
    }

    @Test
    @DisplayName("GET /v1/sessions/{id}/messages - 남의 세션이면 403")
    void getSessionMessages_forbidden() throws Exception {
        when(sessionMessageHistoryService.getHistory(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(get("/v1/sessions/{sessionId}/messages", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /v1/sessions/active - 활성 세션 없고 이전 종료 세션도 없으면 session_id: null 반환")
    void getActiveSession_noSession_returnsNullSessionId() throws Exception {
        when(sessionService.getActiveSession(TEST_USER_ID))
                .thenReturn(ActiveSessionResponse.noActiveSession(null));

        mockMvc.perform(get("/v1/sessions/active")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session_id").doesNotExist())
                .andExpect(jsonPath("$.data.last_summary_status").doesNotExist());
    }

    @Test
    @DisplayName("GET /v1/sessions/active - 활성 세션 있으면 세션 정보 반환")
    void getActiveSession_hasSession_returnsSession() throws Exception {
        ActiveSessionResponse response = new ActiveSessionResponse(
                TEST_SESSION_ID, "mio", "active", OffsetDateTime.now(), null, 1, null, null,
                OPENING
        );
        when(sessionService.getActiveSession(TEST_USER_ID)).thenReturn(response);

        mockMvc.perform(get("/v1/sessions/active")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session_id").value(TEST_SESSION_ID.toString()))
                .andExpect(jsonPath("$.data.character_id").value("mio"))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.initial_message.message_id").value(OPENING.messageId().toString()))
                .andExpect(jsonPath("$.data.initial_message.kind").value("session_opening"))
                .andExpect(jsonPath("$.data.initial_message.content").value(OPENING.content()));
    }

    @Test
    @DisplayName("GET /v1/sessions/active - 인사 없는 기존 세션은 initial_message 를 내려보내지 않는다")
    void getActiveSession_legacySession_omitsInitialMessage() throws Exception {
        ActiveSessionResponse response = new ActiveSessionResponse(
                TEST_SESSION_ID, "mio", "active", OffsetDateTime.now(), null, 4, null, null, null
        );
        when(sessionService.getActiveSession(TEST_USER_ID)).thenReturn(response);

        mockMvc.perform(get("/v1/sessions/active")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session_id").value(TEST_SESSION_ID.toString()))
                .andExpect(jsonPath("$.data.initial_message").doesNotExist());
    }

    @Test
    @DisplayName("GET /v1/sessions/active - 활성 세션 없고 마지막 종료 세션 요약이 done 이면 last_summary_status 반환")
    void getActiveSession_noActiveSession_returnsLastSummaryStatus() throws Exception {
        ActiveSessionResponse response = new ActiveSessionResponse(
                null, null, null, null, null, null, "done", TEST_SESSION_ID, null
        );
        when(sessionService.getActiveSession(TEST_USER_ID)).thenReturn(response);

        mockMvc.perform(get("/v1/sessions/active")
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session_id").doesNotExist())
                .andExpect(jsonPath("$.data.last_summary_status").value("done"))
                .andExpect(jsonPath("$.data.last_ended_session_id").value(TEST_SESSION_ID.toString()));
    }

    @Test
    @DisplayName("POST /v1/sessions - 세션 생성 성공 시 201 반환")
    void createSession_success_returns201() throws Exception {
        SessionResponse response = new SessionResponse(
                TEST_SESSION_ID, "mio", "active", OffsetDateTime.now(), OPENING);
        when(sessionService.createSession(eq(TEST_USER_ID), any())).thenReturn(response);

        mockMvc.perform(post("/v1/sessions")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"character_id\":\"mio\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.session_id").value(TEST_SESSION_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.initial_message.role").value("assistant"))
                .andExpect(jsonPath("$.data.initial_message.kind").value("session_opening"))
                .andExpect(jsonPath("$.data.initial_message.content").value(OPENING.content()))
                // opening_variant 는 내부 관측 값이라 응답에 담지 않는다 (이슈 #530)
                .andExpect(jsonPath("$.data.initial_message.opening_variant").doesNotExist())
                .andExpect(jsonPath("$.data.initial_message.variant").doesNotExist());
    }

    @Test
    @DisplayName("POST /v1/sessions - 잘못된 입력이면 400 반환")
    void createSession_invalidRequest_returns400() throws Exception {
        String invalidCharacterId = "x".repeat(51);

        mockMvc.perform(post("/v1/sessions")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"character_id\":\"" + invalidCharacterId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /v1/sessions - 온보딩 미완료 시 403 반환")
    void createSession_onboardingRequired_returns403() throws Exception {
        when(sessionService.createSession(eq(TEST_USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.ONBOARDING_REQUIRED));

        mockMvc.perform(post("/v1/sessions")
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"character_id\":\"mio\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"));
    }

    @Test
    @DisplayName("POST /v1/sessions - 인증 principal이 없으면 401 반환")
    void createSession_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"character_id\":\"mio\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("POST /v1/sessions/{id}/end - 세션 종료 성공 시 200 반환")
    void endSession_success_returns200() throws Exception {
        EndSessionResponse response = new EndSessionResponse(
                TEST_SESSION_ID, "ended", OffsetDateTime.now(), 5, 300L, "pending"
        );
        when(sessionService.endSession(eq(TEST_USER_ID), eq(TEST_SESSION_ID))).thenReturn(response);

        mockMvc.perform(post("/v1/sessions/{id}/end", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ended"))
                .andExpect(jsonPath("$.data.summary_status").value("pending"));
    }

    @Test
    @DisplayName("POST /v1/sessions/{id}/end - 이미 종료된 세션은 410 반환")
    void endSession_alreadyEnded_returns410() throws Exception {
        when(sessionService.endSession(eq(TEST_USER_ID), eq(TEST_SESSION_ID)))
                .thenThrow(new BusinessException(ErrorCode.SESSION_ALREADY_ENDED));

        mockMvc.perform(post("/v1/sessions/{id}/end", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("GONE"));
    }

    @Test
    @DisplayName("GET /v1/sessions/{id}/summary - 요약 pending 상태면 202 반환")
    void getSessionSummary_pending_returns202() throws Exception {
        SessionSummaryResponse response = new SessionSummaryResponse(
                TEST_SESSION_ID, "pending", "pending", "pending", "pending", "pending",
                OffsetDateTime.now(), "{}", OffsetDateTime.now(), 300L, 5,
                null, null, null, null, null, null, null, List.of()
        );
        when(sessionService.getSessionSummary(eq(TEST_USER_ID), eq(TEST_SESSION_ID))).thenReturn(response);

        mockMvc.perform(get("/v1/sessions/{id}/summary", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.summary_status").value("pending"))
                .andExpect(jsonPath("$.data.core_summary_status").value("pending"))
                .andExpect(jsonPath("$.data.summary").doesNotExist());
    }

    @Test
    @DisplayName("GET /v1/sessions/{id}/summary - 요약 done→viewed 전환 시 200 반환")
    void getSessionSummary_done_returns200AndTransitionsToViewed() throws Exception {
        SessionSummaryResponse response = new SessionSummaryResponse(
                TEST_SESSION_ID, "viewed", "done", "done", "done", "done",
                OffsetDateTime.now(), "{\"todo\":\"TODO_GENERATION_FAILED\"}",
                OffsetDateTime.now(), 300L, 5,
                "세션 요약 내용", null, 70, "[]", false, null, null, List.of()
        );
        when(sessionService.getSessionSummary(eq(TEST_USER_ID), eq(TEST_SESSION_ID))).thenReturn(response);

        mockMvc.perform(get("/v1/sessions/{id}/summary", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary_status").value("viewed"))
                .andExpect(jsonPath("$.data.core_summary_status").value("done"))
                .andExpect(jsonPath("$.data.component_errors.todo")
                        .value("TODO_GENERATION_FAILED"))
                .andExpect(jsonPath("$.data.summary").value("세션 요약 내용"));
    }

    @Test
    @DisplayName("GET /v1/sessions/{id}/summary - 요약 생성 실패 시 410 반환")
    void getSessionSummary_failed_returns410() throws Exception {
        when(sessionService.getSessionSummary(eq(TEST_USER_ID), eq(TEST_SESSION_ID)))
                .thenThrow(new BusinessException(ErrorCode.SESSION_SUMMARY_FAILED));

        mockMvc.perform(get("/v1/sessions/{id}/summary", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("GONE"));
    }

    @Test
    @DisplayName("GET /v1/sessions/{id}/summary - 활성 세션 요약 조회 시 404 반환")
    void getSessionSummary_activeSession_returns404() throws Exception {
        when(sessionService.getSessionSummary(eq(TEST_USER_ID), eq(TEST_SESSION_ID)))
                .thenThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        mockMvc.perform(get("/v1/sessions/{id}/summary", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /v1/sessions/{id}/messages - 같은 세션이 처리 중이면 409 JSON 으로 알린다")
    void sendMessage_sessionBusy_returnsConflictJson() throws Exception {
        doThrow(new BusinessException(ErrorCode.SESSION_MESSAGE_IN_PROGRESS))
                .when(sessionService).acquireTurnLock(eq(TEST_SESSION_ID), any());

        mockMvc.perform(post("/v1/sessions/{sessionId}/messages", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("안녕"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SESSION_MESSAGE_IN_PROGRESS"));
    }

    @Test
    @DisplayName("POST /v1/sessions/{id}/messages - 락은 스트림을 열기 전에 잡는다")
    void sendMessage_acquiresLockBeforeOpeningStream() throws Exception {
        when(sessionService.acquireTurnLock(eq(TEST_SESSION_ID), any())).thenReturn("token");

        mockMvc.perform(post("/v1/sessions/{sessionId}/messages", TEST_SESSION_ID)
                        .principal(() -> TEST_USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("안녕"))))
                .andExpect(status().isOk());

        // 스트림이 시작된 뒤에 잡으면 상태 코드를 바꿀 수 없어, 겹친 요청이 이유 없이 끊긴
        // 스트림만 받게 된다. 순서를 고정한다.
        InOrder inOrder = inOrder(sessionService);
        inOrder.verify(sessionService).validateMessageRequest(eq(TEST_USER_ID), eq(TEST_SESSION_ID), any());
        inOrder.verify(sessionService).acquireTurnLock(eq(TEST_SESSION_ID), any());
    }
}
