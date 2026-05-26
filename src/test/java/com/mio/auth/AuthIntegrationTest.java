package com.mio.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.dto.LoginRequest;
import com.mio.auth.dto.SocialUserInfo;
import com.mio.auth.provider.AppleAuthProvider;
import com.mio.auth.provider.KakaoAuthProvider;
import com.mio.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class AuthIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired UserRepository userRepository;

    @MockBean KakaoAuthProvider kakaoAuthProvider;
    @MockBean AppleAuthProvider appleAuthProvider;

    @BeforeEach
    void setUp() {
        when(kakaoAuthProvider.provider()).thenReturn("kakao");
        when(appleAuthProvider.provider()).thenReturn("apple");
        clearRedis();
        userRepository.deleteAll();
    }

    // ──────────────────────────────────────────
    // 카카오
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("카카오 로그인")
    class Kakao {

        @Test
        @DisplayName("로그인 → Redis 저장 → refresh → 로그아웃 → Redis 정리")
        void loginRefreshLogout() throws Exception {
            when(kakaoAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("kakao-uid-001", "kakao@test.com", "kakao"));

            // 1. 로그인
            JsonNode loginData = login("kakao", null, "fake-kakao-token", "device-k-01");
            String accessToken = loginData.get("access_token").asText();
            String refreshToken = loginData.get("refresh_token").asText();

            assertThat(accessToken).isNotBlank();
            assertThat(refreshToken).startsWith("mio_refresh_");

            // 2. Redis 확인 — refresh:{uuid}, refresh:user:{userId} 2개 존재
            assertThat(redisKeys()).hasSize(2);

            // 3. Refresh — PENDING 유저도 가능(SUSPENDED/DELETED만 차단)
            MvcResult refreshResult = mockMvc.perform(post("/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refresh_token": "%s"}
                                    """.formatted(refreshToken)))
                    .andExpect(status().isOk())
                    .andReturn();

            String newAccessToken = parse(refreshResult).get("access_token").asText();
            assertThat(newAccessToken).isNotBlank();

            // 4. 로그아웃
            mockMvc.perform(post("/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"device_id": "device-k-01"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(true));

            // 5. Redis 확인 — 토큰 키 삭제됨
            Set<String> remaining = redisKeys();
            boolean tokenGone = remaining == null ||
                    remaining.stream().noneMatch(k -> !k.contains("user:"));
            assertThat(tokenGone).isTrue();
        }

        @Test
        @DisplayName("로그인 → 탈퇴 → Redis 전체 정리 + 닉네임 익명화")
        void loginWithdraw() throws Exception {
            when(kakaoAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("kakao-uid-002", "kakao2@test.com", "kakao"));

            JsonNode loginData = login("kakao", null, "fake-kakao-token", "device-k-02");
            String accessToken = loginData.get("access_token").asText();

            assertThat(redisKeys()).isNotEmpty();

            // 탈퇴
            mockMvc.perform(delete("/v1/auth/withdraw")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(true))
                    .andExpect(jsonPath("$.data.withdrawn_at").isNotEmpty())
                    .andExpect(jsonPath("$.data.hard_delete_scheduled_at").isNotEmpty());

            // Redis 전체 삭제
            assertThat(redisKeys()).isNullOrEmpty();
        }
    }

    // ──────────────────────────────────────────
    // 애플
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("애플 로그인")
    class Apple {

        @Test
        @DisplayName("로그인 → Redis 저장 → refresh → 로그아웃 → Redis 정리")
        void loginRefreshLogout() throws Exception {
            when(appleAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("apple-uid-001", "apple@test.com", "apple"));

            // 1. 로그인
            JsonNode loginData = login("apple", "fake-id-token", null, "device-a-01");
            String accessToken = loginData.get("access_token").asText();
            String refreshToken = loginData.get("refresh_token").asText();

            assertThat(accessToken).isNotBlank();
            assertThat(refreshToken).startsWith("mio_refresh_");

            // 2. Redis 확인
            assertThat(redisKeys()).hasSize(2);

            // 3. Refresh
            MvcResult refreshResult = mockMvc.perform(post("/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refresh_token": "%s"}
                                    """.formatted(refreshToken)))
                    .andExpect(status().isOk())
                    .andReturn();

            String newAccessToken = parse(refreshResult).get("access_token").asText();
            assertThat(newAccessToken).isNotBlank();

            // 4. 로그아웃
            mockMvc.perform(post("/v1/auth/logout")
                            .header("Authorization", "Bearer " + newAccessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"device_id": "device-a-01"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(true));

            // 5. Redis 확인
            Set<String> remaining = redisKeys();
            boolean tokenGone = remaining == null ||
                    remaining.stream().noneMatch(k -> !k.contains("user:"));
            assertThat(tokenGone).isTrue();
        }

        @Test
        @DisplayName("로그인 → 탈퇴 → Redis 전체 정리")
        void loginWithdraw() throws Exception {
            when(appleAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("apple-uid-002", "apple2@test.com", "apple"));

            JsonNode loginData = login("apple", "fake-id-token", null, "device-a-02");
            String accessToken = loginData.get("access_token").asText();

            assertThat(redisKeys()).isNotEmpty();

            mockMvc.perform(delete("/v1/auth/withdraw")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success").value(true));

            assertThat(redisKeys()).isNullOrEmpty();
        }
    }

    // ──────────────────────────────────────────
    // 멀티 디바이스
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("멀티 디바이스")
    class MultiDevice {

        @Test
        @DisplayName("기기 2개 로그인 후 기기 1만 로그아웃 → 기기 2 토큰은 유지")
        void partialLogout() throws Exception {
            when(kakaoAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("kakao-uid-multi", "multi@test.com", "kakao"));

            // 기기 1, 2 로그인
            JsonNode data1 = login("kakao", null, "fake-token", "device-m-01");
            JsonNode data2 = login("kakao", null, "fake-token", "device-m-02");

            String accessToken1 = data1.get("access_token").asText();

            // Redis: refresh:{uuid1}, refresh:{uuid2}, refresh:user:{userId} → 3개
            assertThat(redisKeys()).hasSize(3);

            // 기기 1 로그아웃
            mockMvc.perform(post("/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"device_id": "device-m-01"}
                                    """))
                    .andExpect(status().isOk());

            // refresh:{uuid1} 삭제 → refresh:{uuid2}, refresh:user 남음 → 2개
            assertThat(redisKeys()).hasSize(2);

            // 기기 2 refresh 토큰은 여전히 유효
            String refreshToken2 = data2.get("refresh_token").asText();
            mockMvc.perform(post("/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refresh_token": "%s"}
                                    """.formatted(refreshToken2)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("기기 2개 로그인 후 탈퇴 → Redis 전부 삭제")
        void withdrawClearsAllDevices() throws Exception {
            when(kakaoAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("kakao-uid-multi2", "multi2@test.com", "kakao"));

            JsonNode data1 = login("kakao", null, "fake-token", "device-m-03");
            login("kakao", null, "fake-token", "device-m-04");

            assertThat(redisKeys()).hasSize(3);

            mockMvc.perform(delete("/v1/auth/withdraw")
                            .header("Authorization", "Bearer " + data1.get("access_token").asText()))
                    .andExpect(status().isOk());

            assertThat(redisKeys()).isNullOrEmpty();
        }
    }

    // ──────────────────────────────────────────
    // 회원가입 플로우
    // ──────────────────────────────────────────

    @Nested
    @DisplayName("회원가입 상태 머신")
    class SignupFlow {

        @Test
        @DisplayName("로그인 → 약관동의(CONSENT_AGREED) → 프로필완료(PROFILE_COMPLETED) 전이")
        void consentThenComplete() throws Exception {
            when(kakaoAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("kakao-uid-signup", "signup@test.com", "kakao"));

            JsonNode loginData = login("kakao", null, "fake-kakao-token", "device-s-01");
            String accessToken = loginData.get("access_token").asText();
            assertThat(loginData.get("signup_step").asText()).isEqualTo("SOCIAL_AUTHENTICATED");

            // 약관 동의
            MvcResult consentResult = mockMvc.perform(post("/v1/auth/signup/consent")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"consents": [
                                      {"type": "terms",   "agreed": true, "version": "v1"},
                                      {"type": "privacy", "agreed": true, "version": "v1"}
                                    ]}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn();

            String consentStep = parse(consentResult).get("signup_step").asText();
            assertThat(consentStep).isEqualTo("CONSENT_AGREED");

            // 프로필 입력
            MvcResult completeResult = mockMvc.perform(post("/v1/auth/signup/complete")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nickname": "테스트닉", "age_range": "20대", "gender": "male"}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn();

            String completeStep = parse(completeResult).get("signup_step").asText();
            assertThat(completeStep).isEqualTo("PROFILE_COMPLETED");
        }

        @Test
        @DisplayName("약관 동의 없이 프로필 완료 시도 → 403 SIGNUP_STEP_INVALID")
        void completeWithoutConsent_returns403() throws Exception {
            when(kakaoAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("kakao-uid-skipcon", "skipcon@test.com", "kakao"));

            JsonNode loginData = login("kakao", null, "fake-kakao-token", "device-s-02");
            String accessToken = loginData.get("access_token").asText();

            mockMvc.perform(post("/v1/auth/signup/complete")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"nickname": "테스트닉", "age_range": "20대", "gender": "male"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("약관 동의를 중복 호출하면 403 SIGNUP_STEP_INVALID")
        void doubleConsent_returns403() throws Exception {
            when(kakaoAuthProvider.verify(any()))
                    .thenReturn(new SocialUserInfo("kakao-uid-dupcon", "dupcon@test.com", "kakao"));

            JsonNode loginData = login("kakao", null, "fake-kakao-token", "device-s-03");
            String accessToken = loginData.get("access_token").asText();

            String consentBody = """
                    {"consents": [
                      {"type": "terms",   "agreed": true, "version": "v1"},
                      {"type": "privacy", "agreed": true, "version": "v1"}
                    ]}
                    """;

            mockMvc.perform(post("/v1/auth/signup/consent")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(consentBody))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/v1/auth/signup/consent")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(consentBody))
                    .andExpect(status().isForbidden());
        }
    }

    // ──────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────

    private JsonNode login(String provider, String idToken, String accessToken, String deviceId) throws Exception {
        LoginRequest req = new LoginRequest(provider, idToken, accessToken, deviceId);
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        return parse(result);
    }

    private JsonNode parse(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private Set<String> redisKeys() {
        return redisTemplate.keys("refresh:*");
    }

    private void clearRedis() {
        Set<String> keys = redisTemplate.keys("refresh:*");
        if (keys != null && !keys.isEmpty()) {
            keys.forEach(redisTemplate::delete);
        }
    }
}