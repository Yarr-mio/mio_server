package com.mio.memorycontrol.service;

import com.mio.common.audit.AuditLogService;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.memorycontrol.domain.MemoryCorrection;
import com.mio.memorycontrol.dto.MemoryConsentWithdrawResponse;
import com.mio.memorycontrol.dto.MemoryItemResponse;
import com.mio.memorycontrol.dto.MemoryListResponse;
import com.mio.memorycontrol.dto.MemoryUpdateRequest;
import com.mio.memorycontrol.dto.MemoryUpdateResponse;
import com.mio.memorycontrol.repository.MemoryCorrectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 사용자 장기 기억 통제 — 조회·정정·비활성화·동의 철회 (이슈 #453, 로드맵 §12 P0-6).
 *
 * <p>정정·비활성화는 행을 지우지 않는 soft-disable 이다. 검색기 3종(Vector/Lexical/Structured)이
 * active 상태만 회수하므로, 상태 전이만으로 다음 턴 프롬프트 주입에서 즉시 빠진다.
 * 임베딩 재색인은 비동기 후속 작업(TODO — 이슈 #453 본문 참조)이다.
 *
 * <p>상태값은 DB CHECK 제약 대상이라 쓰기 전에 애플리케이션에서 화이트리스트로 검증한다 —
 * 미검증 값이 CHECK 에 걸리면 트랜잭션 전체가 롤백된다(이슈 #219 계열 사고).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryControlService {

    static final String TYPE_SUMMARY = "summary";
    static final String TYPE_EPISODE = "episode";
    static final String TYPE_BELIEF = "belief";

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_CORRECTED = "corrected";
    private static final String STATUS_DISABLED = "disabled";
    /** DB CHECK 허용값과 동일한 쓰기 화이트리스트 (이슈 #219 재발 방지). */
    private static final Set<String> WRITABLE_STATUSES = Set.of(STATUS_CORRECTED, STATUS_DISABLED);

    private static final String ACTION_CORRECT = "correct";
    private static final String ACTION_DISABLE = "disable";

    private static final int MAX_PAGE_SIZE = 100;

    private static final Map<String, String> SOURCE_BY_TYPE = Map.of(
            TYPE_SUMMARY, "session_summary",
            TYPE_EPISODE, "extracted_thought",
            TYPE_BELIEF, "user_belief");

    private static final String LIST_BODY = """
            FROM (
                SELECT ss.id, '%s' AS type,
                       COALESCE(ss.user_summary_text, ss.summary_text) AS content_plain,
                       NULL::bytea AS content_ciphertext,
                       ss.memory_status AS status, ss.session_id, ss.created_at
                FROM session_summaries ss WHERE ss.user_id = ?
                UNION ALL
                SELECT t.id, '%s', NULL, t.thought_text_ciphertext,
                       t.memory_status, t.session_id, t.created_at
                FROM thoughts t WHERE t.user_id = ?
                UNION ALL
                SELECT b.id, '%s', NULL, b.belief_text_ciphertext,
                       b.status, NULL::uuid, b.created_at
                FROM user_beliefs b WHERE b.user_id = ?
            ) m
            """.formatted(TYPE_SUMMARY, TYPE_EPISODE, TYPE_BELIEF);

    private final JdbcTemplate jdbcTemplate;
    private final MessageEncryptor messageEncryptor;
    private final MemoryCorrectionRepository correctionRepository;
    private final AuditLogService auditLogService;

    // ── 조회 ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MemoryListResponse listMemories(UUID userId, int page, int size) {
        validatePaging(page, size);

        // UNION 을 두 번 돌리지 않는다 — 총 건수는 페이지 쿼리의 윈도우 집계로 함께 읽는다.
        long[] totalHolder = {0};
        List<MemoryItemResponse> rows = jdbcTemplate.query(
                "SELECT *, COUNT(*) OVER () AS total_count " + LIST_BODY
                        + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    totalHolder[0] = rs.getLong("total_count");
                    return mapRow(rs, rowNum);
                },
                userId, userId, userId, size, (long) page * size);
        // 범위 밖 페이지는 행이 없어 윈도우 값을 못 읽는다 — 그때만 카운트를 따로 조회한다.
        long total = rows.isEmpty() ? countMemories(userId) : totalHolder[0];

        List<MemoryItemResponse> items = attachCorrections(userId, rows);
        auditLogService.record(userId, "memory_view", "memory_list", userId.toString(),
                Map.of("page", page, "size", size, "returned", items.size()));
        return new MemoryListResponse(items, page, size, total);
    }

    private long countMemories(UUID userId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) " + LIST_BODY, Long.class, userId, userId, userId);
        return total == null ? 0 : total;
    }

    private void validatePaging(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "page는 0 이상, size는 1~" + MAX_PAGE_SIZE + " 이어야 합니다.");
        }
    }

    private MemoryItemResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String type = rs.getString("type");
        String content = rs.getString("content_plain");
        if (content == null) {
            content = decryptOrNull(rs.getBytes("content_ciphertext"), id);
        }
        return new MemoryItemResponse(
                id, type, content, null,
                rs.getString("status"),
                SOURCE_BY_TYPE.getOrDefault(type, type),
                rs.getObject("session_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    /**
     * 소유자 본인 조회이므로 복호화해 반환한다. 실패한 항목은 목록에서 숨기지 않고
     * 본문만 비운다 — 무엇이 저장돼 있는지 자체가 통제의 대상이다.
     */
    private String decryptOrNull(byte[] ciphertext, UUID memoryId) {
        if (ciphertext == null) return null;
        try {
            return new String(messageEncryptor.decrypt(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("MemoryControlService: decrypt failed for memoryId={}", memoryId);
            return null;
        }
    }

    private List<MemoryItemResponse> attachCorrections(UUID userId, List<MemoryItemResponse> rows) {
        if (rows.isEmpty()) return rows;
        List<UUID> ids = rows.stream().map(MemoryItemResponse::id).toList();
        Map<UUID, String> latestByMemory = new HashMap<>();
        for (MemoryCorrection correction :
                correctionRepository.findByUserIdAndMemoryIdInOrderByCreatedAtDesc(userId, ids)) {
            latestByMemory.putIfAbsent(correction.getMemoryId(),
                    decryptOrNull(correction.getCorrectedTextCiphertext(), correction.getMemoryId()));
        }
        return rows.stream()
                .map(item -> latestByMemory.containsKey(item.id())
                        ? new MemoryItemResponse(item.id(), item.type(), item.content(),
                                latestByMemory.get(item.id()), item.status(), item.source(),
                                item.sessionId(), item.createdAt())
                        : item)
                .toList();
    }

    // ── 정정·비활성화 ─────────────────────────────────────────────

    @Transactional
    public MemoryUpdateResponse updateMemory(UUID userId, UUID memoryId, MemoryUpdateRequest request) {
        String action = request.action();
        if (!ACTION_CORRECT.equals(action) && !ACTION_DISABLE.equals(action)) {
            throw new BusinessException(ErrorCode.INVALID_MEMORY_ACTION);
        }
        String type = locateMemoryType(userId, memoryId);

        String newStatus;
        if (ACTION_CORRECT.equals(action)) {
            saveCorrection(userId, memoryId, type, request.correctedText());
            newStatus = STATUS_CORRECTED;
        } else {
            newStatus = STATUS_DISABLED;
        }
        transitionStatus(userId, memoryId, type, newStatus);

        auditLogService.record(userId, "memory_" + action, "memory_" + type, memoryId.toString(),
                Map.of("type", type, "new_status", newStatus));
        return new MemoryUpdateResponse(memoryId, type, newStatus);
    }

    /** 소유권 검증을 겸한다 — 남의 기억은 존재 자체를 알려주지 않고 NOT_FOUND 로 답한다. */
    private String locateMemoryType(UUID userId, UUID memoryId) {
        if (exists("SELECT COUNT(*) FROM session_summaries WHERE id = ? AND user_id = ?", memoryId, userId)) {
            return TYPE_SUMMARY;
        }
        if (exists("SELECT COUNT(*) FROM thoughts WHERE id = ? AND user_id = ?", memoryId, userId)) {
            return TYPE_EPISODE;
        }
        if (exists("SELECT COUNT(*) FROM user_beliefs WHERE id = ? AND user_id = ?", memoryId, userId)) {
            return TYPE_BELIEF;
        }
        throw new BusinessException(ErrorCode.MEMORY_NOT_FOUND);
    }

    private boolean exists(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null && count > 0;
    }

    private void saveCorrection(UUID userId, UUID memoryId, String type, String correctedText) {
        if (correctedText == null || correctedText.isBlank()) {
            throw new BusinessException(ErrorCode.MEMORY_CORRECTION_TEXT_REQUIRED);
        }
        correctionRepository.save(MemoryCorrection.builder()
                .userId(userId)
                .memoryType(type)
                .memoryId(memoryId)
                .correctedTextCiphertext(
                        messageEncryptor.encrypt(correctedText.trim().getBytes(StandardCharsets.UTF_8)))
                .correctedTextDekId(messageEncryptor.dekId())
                .build());
    }

    private void transitionStatus(UUID userId, UUID memoryId, String type, String newStatus) {
        requireWritableStatus(newStatus);
        String sql = switch (type) {
            case TYPE_SUMMARY -> "UPDATE session_summaries SET memory_status = ? WHERE id = ? AND user_id = ?";
            case TYPE_EPISODE -> "UPDATE thoughts SET memory_status = ? WHERE id = ? AND user_id = ?";
            case TYPE_BELIEF -> "UPDATE user_beliefs SET status = ? WHERE id = ? AND user_id = ?";
            default -> throw new BusinessException(ErrorCode.MEMORY_NOT_FOUND);
        };
        int updated = jdbcTemplate.update(sql, newStatus, memoryId, userId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.MEMORY_NOT_FOUND);
        }
    }

    private void requireWritableStatus(String status) {
        if (!WRITABLE_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.INVALID_MEMORY_ACTION,
                    "허용되지 않은 기억 상태 전이입니다: " + status);
        }
    }

    // ── 동의 철회 ─────────────────────────────────────────────────

    /**
     * 동의를 철회하고 기존 장기 기억을 전부 비활성화한다. 멱등 — 최초 철회 시각은 유지된다.
     * 신규 적재 중단은 {@code SessionConsolidator} 가 {@code memory_retention_agreed} 를 보고
     * 세션 종료 시점에 게이트한다.
     */
    @Transactional
    public MemoryConsentWithdrawResponse withdrawConsent(UUID userId) {
        // RETURNING 으로 최초 철회 시각을 upsert 와 한 문장에서 읽는다 (별도 SELECT 불필요).
        OffsetDateTime withdrawnAt = jdbcTemplate.queryForObject(
                """
                INSERT INTO user_memory_preferences (user_id, memory_retention_agreed, memory_consent_withdrawn_at)
                VALUES (?, false, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET memory_retention_agreed = false,
                    memory_consent_withdrawn_at =
                        COALESCE(user_memory_preferences.memory_consent_withdrawn_at,
                                 EXCLUDED.memory_consent_withdrawn_at),
                    updated_at = now()
                RETURNING memory_consent_withdrawn_at
                """,
                OffsetDateTime.class, userId, OffsetDateTime.now(ZoneOffset.UTC));

        // 아래 UPDATE 의 'disabled' 리터럴은 WRITABLE_STATUSES 화이트리스트에 포함된
        // 컴파일 타임 상수라 별도 런타임 검증이 무의미하다 (transitionStatus 와 달리
        // 호출자 입력이 섞이지 않는 경로).
        long disabled = 0;
        disabled += jdbcTemplate.update(
                "UPDATE session_summaries SET memory_status = ? WHERE user_id = ? AND memory_status = ?",
                STATUS_DISABLED, userId, STATUS_ACTIVE);
        disabled += jdbcTemplate.update(
                "UPDATE thoughts SET memory_status = ? WHERE user_id = ? AND memory_status = ?",
                STATUS_DISABLED, userId, STATUS_ACTIVE);
        disabled += jdbcTemplate.update(
                "UPDATE user_beliefs SET status = ? WHERE user_id = ? AND status = ?",
                STATUS_DISABLED, userId, STATUS_ACTIVE);

        auditLogService.record(userId, "memory_consent_withdraw", "memory_consent", userId.toString(),
                Map.of("disabled_count", disabled));
        return new MemoryConsentWithdrawResponse(withdrawnAt, disabled);
    }
}
