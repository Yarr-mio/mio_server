package com.mio.mypage.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserProfileUpdateResponse(
        UUID userId,
        String nickname,
        String ageRange,
        OffsetDateTime updatedAt
) {}
