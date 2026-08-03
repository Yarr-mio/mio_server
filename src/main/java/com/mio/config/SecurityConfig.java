package com.mio.config;

import com.mio.auth.filter.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private static final String[] PUBLIC_ENDPOINTS = {
            // actuator 는 관리 포트로 분리됐다. 8080 의 liveness 는 HealthController 가 담당한다.
            "/health",
            "/error",
            "/v1/auth/**",
            "/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * 관리 포트 전용 체인.
     *
     * <p>{@code EndpointRequest} 는 관리 포트가 메인 포트와 다르면 메인 포트 요청에는 매칭되지
     * 않는다. 따라서 이 체인은 관리 포트에만 적용되고, 8080 의 {@code /actuator/**} 는 아래 메인
     * 체인의 {@code anyRequest().authenticated()} 에 그대로 걸린다.
     *
     * <p>관리 포트를 인증 없이 여는 근거: Nginx 는 8080 만 프록시하고 관리 포트는
     * {@code docker-compose.prod.yml} 에 publish 하지 않으므로 컨테이너 밖에서 도달할 수 없다.
     * <b>이 포트를 publish 하게 되면 이 permitAll 부터 걷어내야 한다.</b>
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // 이슈 #279: users.is_admin 플래그로 발급된 JWT(scope에 "admin" 포함)만 통과.
                        // 플래그는 셀프서비스 승격 경로 없이 DB에서 직접 세운다.
                        .requestMatchers("/v1/admin/**").hasAuthority("ROLE_ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
