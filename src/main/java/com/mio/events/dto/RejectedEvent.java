package com.mio.events.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 이슈 #324 — event_id가 없어서(MISSING_EVENT_ID) 거부된 건은 event_id가 null로 돌아온다.
 * index(배치 내 0-base 위치) 없이는 앱이 큐에서 어느 항목을 지울지 몰라 영구 재전송 루프에
 * 빠질 수 있다.
 */
public record RejectedEvent(
        int index,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_name") String eventName,
        String reason
) {
}
