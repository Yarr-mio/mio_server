package com.mio.memorycontrol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 사용자 장기 기억 한 건 (이슈 #453, 로드맵 §12 P0-6).
 *
 * <p>{@code content} 는 소유자 본인 조회이므로 복호화해 반환한다. 복호화에 실패한 항목은
 * 목록에서 빼지 않고 {@code content=null} 로 남긴다 — "서버가 나에 대해 무엇을 기억하는가"
 * 를 보여주는 API 가 항목 자체를 숨기면 통제권이 성립하지 않는다.
 *
 * @param type   summary(세션 요약) | episode(추출된 사고) | belief(신념)
 * @param source 출처 테이블 수준의 provenance
 * @param status active | corrected | disabled (belief 는 dormant/revised/retired 도 가능)
 */
public record MemoryItemResponse(
        UUID id,
        String type,
        String content,
        @JsonProperty("corrected_text") String correctedText,
        String status,
        String source,
        @JsonProperty("session_id") UUID sessionId,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {}
