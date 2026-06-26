package com.mio.ai.memory.consolidation;

import com.mio.ai.AiCacheKeys;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.domain.Session;
import com.mio.session.domain.SessionCheckpoint;
import com.mio.session.repository.SessionCheckpointRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 20개 메시지(10턴)마다 중간 요약(체크포인트)을 비동기로 생성한다.
 * 세션 종료 시 SessionConsolidator가 체크포인트 + 잔여 메시지로 최종 요약을 만든다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationCheckpointService {

    static final int CHECKPOINT_INTERVAL = 20;
    private static final String CHECKPOINT_MODEL = "gpt-4o-mini";
    private static final String CHECKPOINT_SYSTEM_PROMPT = """
            당신은 CBT 코칭 대화의 중간 요약 전문가입니다.
            아래 대화를 200자 이내로 간결하게 요약하세요.

            포함 내용:
            - 사용자가 표현한 주요 감정과 상황
            - 드러난 인지 패턴 (있다면)
            - 대화의 흐름과 전환점

            원칙:
            - 사용자 원문을 직접 인용하지 않습니다
            - 개인 식별 정보는 포함하지 않습니다
            - 이전 요약의 연속선상에서 맥락을 유지합니다
            """;

    private static final Duration CHECKPOINT_TTL = Duration.ofHours(2);

    private final SessionRepository sessionRepository;
    private final SessionCheckpointRepository checkpointRepository;
    private final UserRepository userRepository;
    private final LlmClient llmClient;
    private final MessageEncryptor messageEncryptor;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    record MessageRecord(String line, OffsetDateTime createdAt) {}

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void maybeCheckpoint(UUID sessionId, UUID userId) {
        try {
            Session session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) return;

            if (session.getMessageCount() % CHECKPOINT_INTERVAL != 0) return;

            SessionCheckpoint latest =
                    checkpointRepository.findTopBySession_IdOrderByCheckpointSeqDesc(sessionId).orElse(null);
            OffsetDateTime since = latest != null ? latest.getCoveredUpToAt() : null;

            // 단일 쿼리로 메시지 + created_at 동시 읽기 (두 번 쿼리 시 사이에 끼는 메시지 갭 방지)
            List<MessageRecord> records = loadMessageRecordsSince(sessionId, since);
            if (records.isEmpty()) return;

            String summaryText = generateSummary(records.stream().map(MessageRecord::line).toList());
            if (summaryText == null) return;
            OffsetDateTime coveredUpTo = records.stream()
                    .map(MessageRecord::createdAt)
                    .max(OffsetDateTime::compareTo)
                    .orElse(null);
            if (coveredUpTo == null) return;

            int seq = (latest != null ? latest.getCheckpointSeq() : 0) + 1;
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) return;

            SessionCheckpoint checkpoint = SessionCheckpoint.builder()
                    .session(session)
                    .user(user)
                    .checkpointSeq(seq)
                    .summaryText(summaryText)
                    .coveredUpToAt(coveredUpTo)
                    .build();
            checkpointRepository.save(checkpoint);
            String cacheKey = AiCacheKeys.CHECKPOINT_CACHE_KEY.formatted(sessionId);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        redisTemplate.opsForValue().set(cacheKey, summaryText, CHECKPOINT_TTL);
                    }
                });
            } else {
                redisTemplate.opsForValue().set(cacheKey, summaryText, CHECKPOINT_TTL);
            }
            log.info("ConversationCheckpointService: saved checkpoint seq={} sessionId={}", seq, sessionId);

        } catch (Exception e) {
            log.warn("ConversationCheckpointService: checkpoint failed sessionId={}", sessionId, e);
        }
    }

    List<MessageRecord> loadMessageRecordsSince(UUID sessionId, OffsetDateTime since) {
        try {
            List<java.util.Map<String, Object>> rows;
            if (since == null) {
                rows = jdbcTemplate.queryForList(
                        "SELECT role, content_ciphertext, created_at FROM messages WHERE session_id = ? ORDER BY created_at ASC",
                        sessionId);
            } else {
                rows = jdbcTemplate.queryForList(
                        "SELECT role, content_ciphertext, created_at FROM messages WHERE session_id = ? AND created_at > ? ORDER BY created_at ASC",
                        sessionId, since);
            }

            List<MessageRecord> records = new ArrayList<>();
            for (var row : rows) {
                String role = (String) row.get("role");
                byte[] cipher = (byte[]) row.get("content_ciphertext");
                OffsetDateTime createdAt = (OffsetDateTime) row.get("created_at");
                if (cipher == null || createdAt == null) continue;
                try {
                    String text = new String(messageEncryptor.decrypt(cipher), StandardCharsets.UTF_8);
                    records.add(new MessageRecord(role + ": " + text, createdAt));
                } catch (Exception ex) {
                    log.debug("Skipping decrypt failure in checkpoint sessionId={}", sessionId);
                }
            }
            return records;
        } catch (Exception e) {
            log.warn("ConversationCheckpointService: failed to load messages sessionId={}", sessionId, e);
            return List.of();
        }
    }

    // SessionConsolidator에서 lines만 필요할 때 사용
    List<String> loadMessageLinesSince(UUID sessionId, OffsetDateTime since) {
        return loadMessageRecordsSince(sessionId, since).stream()
                .map(MessageRecord::line)
                .toList();
    }

    private String generateSummary(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        try {
            llmClient.stream(
                    LlmRequest.of(CHECKPOINT_MODEL, CHECKPOINT_SYSTEM_PROMPT, String.join("\n", lines)),
                    sb::append
            );
        } catch (Exception e) {
            log.warn("ConversationCheckpointService: summary generation failed", e);
            return null;
        }
        String result = sb.toString().trim();
        return result.isBlank() ? null : result;
    }
}
