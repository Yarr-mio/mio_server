package com.mio.admin.service;

import com.mio.admin.dto.CrisisEventReviewRequest;
import com.mio.ai.crisis.CrisisEventRepository;
import com.mio.common.audit.AuditLogService;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.crisis.domain.CrisisEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminCrisisEventService {

    private final CrisisEventRepository crisisEventRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public void review(UUID eventId, CrisisEventReviewRequest request) {
        CrisisEvent event = crisisEventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CRISIS_EVENT_NOT_FOUND));

        if (event.isOperatorReviewed()) {
            throw new BusinessException(ErrorCode.CRISIS_EVENT_ALREADY_REVIEWED);
        }

        event.review(request.action(), request.note());

        auditLogService.record(
                event.getUser().getId(),
                "CRISIS_EVENT_REVIEWED",
                "crisis_event",
                eventId.toString(),
                Map.of(
                        "action", request.action(),
                        "note", request.note() != null ? request.note() : ""
                )
        );
    }
}
