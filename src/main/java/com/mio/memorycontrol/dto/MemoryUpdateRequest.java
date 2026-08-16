package com.mio.memorycontrol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 기억 정정·비활성화 요청 (이슈 #453).
 *
 * @param action        correct(정정) | disable(비활성화) — 허용값 검증은 서비스에서 한다
 * @param correctedText action=correct 일 때 필수인 사용자 제공 수정문
 */
public record MemoryUpdateRequest(
        @NotBlank(message = "action은 필수입니다.") String action,
        @JsonProperty("corrected_text")
        @Size(max = 1000, message = "정정 내용은 1000자 이내여야 합니다.") String correctedText
) {}
