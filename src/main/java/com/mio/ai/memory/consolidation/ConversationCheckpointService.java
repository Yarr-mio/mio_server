package com.mio.ai.memory.consolidation;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
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

    private final SessionRepository sessionRepository;
    private final SessionCheckpointRepository checkpointRepository;
    private final UserRepository userRepository;
    private final LlmClient llmClient;
    private final MessageEncryptor messageEncryptor;
    private final JdbcTemplate jdbcTemplate;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void maybeCheckpoint(UUID sessionId, UUID userId) {
        try {
            Session session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) return;

            if (session.getMessageCount() % CHECKPOINT_INTERVAL != 0) return;

            SessionCheckpoint latest = checkpointRepository.findLatestBySessionId(sessionId).orElse(null);
            OffsetDateTime since = latest != null ? latest.getCoveredUpToAt() : null;

            List<String> lines = loadMessageLinesSince(sessionId, since);
            if (lines.isEmpty()) return;

            String summaryText = generateSummary(String.join("\n", lines));
            OffsetDateTime coveredUpTo = findLastMessageCreatedAt(sessionId, since);
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
            log.info("ConversationCheckpointService: saved checkpoint seq={} sessionId={}", seq, sessionId);

        } catch (Exception e) {
            log.warn("ConversationCheckpointService: checkpoint failed sessionId={}", sessionId, e);
        }
    }

    List<String> loadMessageLinesSince(UUID sessionId, OffsetDateTime since) {
        try {
            List<?> rows;
            if (since == null) {
                rows = jdbcTemplate.queryForList(
                        "SELECT role, content_ciphertext FROM messages WHERE session_id = ? ORDER BY created_at ASC",
                        sessionId);
            } else {
                rows = jdbcTemplate.queryForList(
                        "SELECT role, content_ciphertext FROM messages WHERE session_id = ? AND created_at > ? ORDER BY created_at ASC",
                        sessionId, since);
            }

            List<String> lines = new ArrayList<>();
            for (Object rowObj : rows) {
                @SuppressWarnings("unchecked")
                var row = (java.util.Map<String, Object>) rowObj;
                String role = (String) row.get("role");
                byte[] cipher = (byte[]) row.get("content_ciphertext");
                if (cipher == null) continue;
                try {
                    String text = new String(messageEncryptor.decrypt(cipher), StandardCharsets.UTF_8);
                    lines.add(role + ": " + text);
                } catch (Exception ex) {
                    log.debug("Skipping decrypt failure in checkpoint sessionId={}", sessionId);
                }
            }
            return lines;
        } catch (Exception e) {
            log.warn("ConversationCheckpointService: failed to load messages sessionId={}", sessionId, e);
            return List.of();
        }
    }

    private OffsetDateTime findLastMessageCreatedAt(UUID sessionId, OffsetDateTime since) {
        try {
            if (since == null) {
                return jdbcTemplate.queryForObject(
                        "SELECT MAX(created_at) FROM messages WHERE session_id = ?",
                        OffsetDateTime.class, sessionId);
            }
            return jdbcTemplate.queryForObject(
                    "SELECT MAX(created_at) FROM messages WHERE session_id = ? AND created_at > ?",
                    OffsetDateTime.class, sessionId, since);
        } catch (Exception e) {
            log.warn("ConversationCheckpointService: failed to find last message at sessionId={}", sessionId, e);
            return null;
        }
    }

    private String generateSummary(String conversationText) {
        StringBuilder sb = new StringBuilder();
        try {
            llmClient.stream(
                    LlmRequest.of(CHECKPOINT_MODEL, CHECKPOINT_SYSTEM_PROMPT, conversationText),
                    sb::append
            );
        } catch (Exception e) {
            log.warn("ConversationCheckpointService: summary generation failed", e);
            return "중간 요약을 생성할 수 없습니다.";
        }
        return sb.toString().trim();
    }
}
