package com.mio.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.service.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JwtAuthenticationFilterTest {

    private final TestableJwtAuthenticationFilter filter =
            new TestableJwtAuthenticationFilter(mock(JwtTokenService.class), new ObjectMapper());

    @Test
    @DisplayName("개발용 JWT 발급 엔드포인트는 인증 필터를 건너뛴다")
    void devTokenEndpointSkipsJwtFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/dev/token");

        assertThat(filter.exposedShouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("에러 dispatch는 인증 필터를 건너뛴다")
    void errorDispatchSkipsJwtFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");

        assertThat(filter.exposedShouldNotFilter(request)).isTrue();
    }

    private static class TestableJwtAuthenticationFilter extends JwtAuthenticationFilter {

        TestableJwtAuthenticationFilter(JwtTokenService jwtTokenService, ObjectMapper objectMapper) {
            super(jwtTokenService, objectMapper);
        }

        boolean exposedShouldNotFilter(MockHttpServletRequest request) {
            return shouldNotFilter(request);
        }
    }
}
