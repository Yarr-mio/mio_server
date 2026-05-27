package com.mio.auth.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.dto.SocialUserInfo;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoAuthProvider implements SocialAuthProvider {

    private static final String TOKEN_INFO_PATH = "/v1/user/access_token_info";
    private static final String USER_INFO_PATH = "/v2/user/me";
    private static final int MAX_RETRY = 3;

    @Value("${kakao.api-url}")
    private String kakaoApiUrl;

    @Value("${kakao.app-id}")
    private long kakaoAppId;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public KakaoAuthProvider(ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String provider() {
        return "kakao";
    }

    @Override
    public SocialUserInfo verify(String accessToken) {
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                verifyTokenAppId(accessToken);

                String body = restClient.get()
                        .uri(kakaoApiUrl + USER_INFO_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                            throw new BusinessException(ErrorCode.OAUTH_FAILED);
                        })
                        .body(String.class);

                return parseUserInfo(body);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
            }
        }

        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    // /v1/user/access_token_info로 토큰이 우리 앱에서 발급된 것인지 검증
    private void verifyTokenAppId(String accessToken) {
        String body = restClient.get()
                .uri(kakaoApiUrl + TOKEN_INFO_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    throw new BusinessException(ErrorCode.OAUTH_FAILED);
                })
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.path("appId").asLong() != kakaoAppId) {
                throw new BusinessException(ErrorCode.OAUTH_FAILED);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }

    private SocialUserInfo parseUserInfo(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String socialId = root.path("id").asText();
            if (socialId.isBlank()) {
                throw new BusinessException(ErrorCode.OAUTH_FAILED);
            }
            String email = root.path("kakao_account").path("email").asText(null);
            return new SocialUserInfo(socialId, email, "kakao");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }
}