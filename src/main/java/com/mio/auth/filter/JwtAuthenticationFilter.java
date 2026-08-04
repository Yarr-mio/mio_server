package com.mio.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.service.JwtTokenService;
import com.mio.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    // "METHOD URI" 형식으로 정확히 일치해야 함 — query string은 getRequestURI()에 포함되지 않아 안전
    private static final Set<String> WHITELIST = Set.of(
            "POST /v1/auth/login",
            "POST /v1/auth/refresh",
            "POST /v1/auth/dev/token",
            // actuator 는 관리 포트로 분리됐다. 8080 의 liveness 는 HealthController 가 담당한다.
            "GET /health",
            // 이슈 #285: 성장분석 이벤트 수집 API. SecurityConfig.PUBLIC_ENDPOINTS 와 별개로
            // 이 필터가 먼저 실행되므로 여기서도 제외해야 한다.
            "POST /v1/events"
    );

    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("/error".equals(request.getRequestURI())) {
            return true;
        }
        String key = request.getMethod() + " " + request.getRequestURI();
        return WHITELIST.contains(key);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            sendError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());

        try {
            Claims claims = jwtTokenService.parseToken(token);
            String userId = claims.getSubject();
            String deviceId = claims.get("device_id", String.class);

            List<?> scope = claims.get("scope", List.class);
            List<SimpleGrantedAuthority> authorities = scope != null && scope.contains("admin")
                    ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    : List.of(new SimpleGrantedAuthority("ROLE_USER"));

            // principal=userId, credentials=deviceId — 컨트롤러에서 @AuthenticationPrincipal로 userId 접근
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userId,
                    deviceId,
                    authorities
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            sendError(response, HttpStatus.UNAUTHORIZED, ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException e) {
            sendError(response, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_TOKEN);
        }
    }

    // 필터에서 예외를 throw하면 ExceptionTranslationFilter에 도달하지 않으므로 응답을 직접 작성
    private void sendError(HttpServletResponse response, HttpStatus status, ErrorCode errorCode) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "error", Map.of(
                        "code", errorCode.getCode(),
                        "message", errorCode.getMessage()
                )
        ));
    }
}
