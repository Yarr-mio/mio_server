package com.mio.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.regex.Pattern;

/**
 * 탈퇴 뒤에도 인증 없이 조회할 수 있는 삭제 작업 상태 경로의 단일 계약.
 *
 * <p>Security authorization과 JWT filter가 서로 다른 wildcard/정규식을 쓰면 같은 URL이
 * 한쪽에서는 공개이고 다른 쪽에서는 인증 대상으로 처리된다. 두 계층이 이 matcher를
 * 함께 사용해 GET 단일 리소스만 공개한다. operation id 형식 검증은 MVC가 400으로
 * 응답하므로 잘못된 값도 인증 오류로 숨기지 않는다.
 */
public final class PublicDataDeletionStatusRequestMatcher implements RequestMatcher {

    public static final PublicDataDeletionStatusRequestMatcher INSTANCE =
            new PublicDataDeletionStatusRequestMatcher();

    private static final Pattern SINGLE_RESOURCE =
            Pattern.compile("^/v1/data-deletions/[^/]+$");

    private PublicDataDeletionStatusRequestMatcher() {
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && SINGLE_RESOURCE.matcher(request.getRequestURI()).matches();
    }
}
