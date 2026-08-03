package com.mio.common.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 호출자 트랜잭션과 분리된 독립 트랜잭션으로 기록한다.
     *
     * <p>같은 트랜잭션에서 실행하면, 여기서 예외를 잡아 삼켜도 JPA 구현체가 이미 트랜잭션을
     * rollback-only 로 표시해뒀을 수 있다 — 그러면 감사 로그 실패가 호출자의 실제 작업(탈퇴 등)
     * 커밋까지 {@code UnexpectedRollbackException} 으로 무너뜨린다. 감사 기록 실패가 원 작업을
     * 막아서는 안 되므로 REQUIRES_NEW 로 분리한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
