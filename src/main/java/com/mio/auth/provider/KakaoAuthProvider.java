package com.mio.auth.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.auth.dto.SocialUserInfo;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class KakaoAuthProvider implements SocialAuthProvider {

    private static final String USER_INFO_PATH = "/v2/user/me";
    private static final int MAX_RETRY = 3;

    @Value("${kakao.api-url}")
    private String kakaoApiUrl;

    private final ObjectMapper objectMapper;

    @Override
    public String provider() {
        return "kakao";
    }

    @Override
    public SocialUserInfo verify(String accessToken) {
        RestClient client = RestClient.create();
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            try {
                String body = client.get()
                        .uri(kakaoApiUrl + USER_INFO_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw new BusinessException(ErrorCode.OAUTH_FAILED);
                        })
                        .body(String.class);

                return parseUserInfo(body);
            } catch (BusinessException e) {
                throw e; // 인증 실패(401)는 재시도 불필요, 즉시 전파
            } catch (Exception e) {
                lastException = e;
            }
        }

        throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private SocialUserInfo parseUserInfo(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String socialId = root.path("id").asText();
            String email = root.path("kakao_account").path("email").asText(null);
            return new SocialUserInfo(socialId, email, "kakao");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OAUTH_FAILED);
        }
    }
}