package com.mio.common;

import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;

import java.security.Principal;
import java.util.UUID;

public final class PrincipalUtils {

    private PrincipalUtils() {}

    public static UUID resolveUserId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
