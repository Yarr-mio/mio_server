package com.mio.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 이슈 #328 — RateLimitExceededException 전용 핸들러가 Retry-After 헤더를 정확히 얹는지 검증. */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock private HttpServletRequest request;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("RateLimitExceededException은 429와 Retry-After 헤더(초 단위)를 함께 반환한다")
    void handleRateLimitExceeded_returns429WithRetryAfterHeader() {
        when(request.getAttribute("traceId")).thenReturn("trace-1");

        ResponseEntity<ErrorResponse> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException(37L), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED.getCode());
        assertThat(response.getBody().error().traceId()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("일반 BusinessException(RATE_LIMIT_EXCEEDED)에는 Retry-After 헤더가 붙지 않는다 — 체크인/세션은 영향 없음")
    void handleBusinessException_plainRateLimitExceeded_noRetryAfterHeader() {
        when(request.getAttribute("traceId")).thenReturn("trace-2");

        ResponseEntity<ErrorResponse> response =
                handler.handleBusinessException(new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    @DisplayName("이슈 #348 — X-Device-Id 같은 필수 헤더 누락은 500이 아니라 400을 반환한다")
    void handleServletRequestBindingException_missingHeader_returns400() throws NoSuchMethodException {
        when(request.getAttribute("traceId")).thenReturn("trace-3");
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyEndpoint", String.class), 0);
        MissingRequestHeaderException exception = new MissingRequestHeaderException("X-Device-Id", parameter);

        ResponseEntity<ErrorResponse> response =
                handler.handleServletRequestBindingException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.INVALID_INPUT.getCode());
        assertThat(response.getBody().error().traceId()).isEqualTo("trace-3");
    }

    @SuppressWarnings("unused")
    private void dummyEndpoint(String deviceId) {
    }
}
