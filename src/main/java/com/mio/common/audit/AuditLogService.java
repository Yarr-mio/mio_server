package com.mio.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 컴플라이언스 감사 기록 계층. 탈퇴·동의·위기검토 등 "누가 무엇을 했는가"를 남긴다.
 *
 * <p>성장분석 이벤트 로그(CC·리텐션)와는 별개 파이프라인이다 — 혼용하지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void record(UUID userId, String action, String resourceType, String resourceId,
                        Map<String, Object> details) {
        try {
            AuditLog entry = AuditLog.builder()
                    .userId(userId)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .details(objectMapper.writeValueAsString(details))
                    .build();
            auditLogRepository.save(entry);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit log details: action={}, resourceId={}", action, resourceId, e);
        } catch (Exception e) {
            log.error("Failed to persist audit log: action={}, resourceId={}", action, resourceId, e);
        }
    }
}
