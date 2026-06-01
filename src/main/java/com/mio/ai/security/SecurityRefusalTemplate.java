package com.mio.ai.security;

import org.springframework.stereotype.Component;

@Component
public class SecurityRefusalTemplate {

    private static final String REFUSAL_MESSAGE =
            "죄송해요, 그 요청에는 응답하기 어려워요. 다른 이야기를 나눠볼까요?";

    public String get() {
        return REFUSAL_MESSAGE;
    }
}
