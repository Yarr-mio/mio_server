package com.mio.auth.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.dto.SocialUserInfo;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoAuthProviderTest {

    private static final String KAKAO_API_URL = "https://kapi.kakao.com";
    private static final String USER_INFO_URL = KAKAO_API_URL + "/v2/user/me";

    private KakaoAuthProvider provider;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        provider = new KakaoAuthProvider(new ObjectMapper(), builder);
        ReflectionTestUtils.setField(provider, "kakaoApiUrl", KAKAO_API_URL);
    }

    @Test
    @DisplayName("정상 응답에서 socialId와 email을 파싱한다")
    void verify_validResponse_returnsSocialUserInfo() {
        mockServer.expect(requestTo(USER_INFO_URL))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"id": 123456789, "kakao_account": {"email": "user@kakao.com"}}
                        """, MediaType.APPLICATION_JSON));

        SocialUserInfo result = provider.verify("test-token");

        assertThat(result.socialId()).isEqualTo("123456789");
        assertThat(result.email()).isEqualTo("user@kakao.com");
        assertThat(result.provider()).isEqualTo("kakao");
    }

    @Test
    @DisplayName("email이 없는 계정도 socialId만으로 파싱한다")
    void verify_noEmail_returnsSocialIdOnly() {
        mockServer.expect(requestTo(USER_INFO_URL))
                .andRespond(withSuccess("""
                        {"id": 999888777, "kakao_account": {}}
                        """, MediaType.APPLICATION_JSON));

        SocialUserInfo result = provider.verify("test-token");

        assertThat(result.socialId()).isEqualTo("999888777");
        assertThat(result.email()).isNull();
    }

    @Test
    @DisplayName("응답에 id가 없으면 OAUTH_FAILED를 던진다")
    void verify_missingId_throwsOauthFailed() {
        mockServer.expect(requestTo(USER_INFO_URL))
                .andRespond(withSuccess("""
                        {"kakao_account": {"email": "user@kakao.com"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.verify("test-token"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.OAUTH_FAILED));
    }

    @Test
    @DisplayName("Kakao 서버 401 응답 시 OAUTH_FAILED를 던진다")
    void verify_unauthorizedResponse_throwsOauthFailed() {
        mockServer.expect(requestTo(USER_INFO_URL))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> provider.verify("invalid-token"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.OAUTH_FAILED));
    }

    @Test
    @DisplayName("Kakao 서버 연속 실패 시 UPSTREAM_UNAVAILABLE을 던진다")
    void verify_serverErrorWithRetry_throwsUpstreamUnavailable() {
        // MAX_RETRY = 3 이므로 3번 응답 등록
        for (int i = 0; i < 3; i++) {
            mockServer.expect(requestTo(USER_INFO_URL))
                    .andRespond(withServerError());
        }

        assertThatThrownBy(() -> provider.verify("test-token"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }
}
