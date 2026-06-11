package com.mio.ai.judge;

import com.mio.ai.security.SecurityLevel;

import java.util.List;

public record SecurityVerdict(
        SecurityLevel level,
        List<String> attackTypes,
        boolean requireOutputSecurityGuard
) {
    public static SecurityVerdict clean() {
        return new SecurityVerdict(SecurityLevel.CLEAN, List.of(), false);
    }
}
