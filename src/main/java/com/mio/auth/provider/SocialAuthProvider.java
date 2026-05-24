package com.mio.auth.provider;

import com.mio.auth.dto.SocialUserInfo;

public interface SocialAuthProvider {
    SocialUserInfo verify(String token);
    String provider();
}