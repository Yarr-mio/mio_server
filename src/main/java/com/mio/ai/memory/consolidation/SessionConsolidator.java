package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.CbtPattern;
import com.mio.ai.domain.EmotionalState;
import com.mio.ai.domain.MemoryEmbedding;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.memory.episodic.Thought;
import com.mio.ai.memory.episodic.ThoughtRepository;
import com.mio.ai.memory.episodic.UserBelief;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.domain.SessionSummary;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Tier 1 Worker — 세션 종료 직후 비동기 실행.
 *
 * 처리 순서:
 * 1. 세션 요약 생성 (LLM)
 * 2. summary_ciphertext AES-256 저장
 * 3. ExtractorLLM → thought/distortion/emotion/trigger 추출
 * 4. OntologyValidator 통과 항목만 저장 (optional bean)
 * 5. trigger_tags + episode_type → session_summaries 갱신
 * 6. cbt_patterns.recurrence_count++ (왜곡 유형별)
 * 7. emotional_states INSERT
 * 8. thoughts INSERT (암호화)
 * 9. UserBelief 연결 + BeliefEvidenceAccumulator
 * 10. 임베딩 생성 → memory_embeddings + session_summaries.episode_emb 갱신
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionConsolidator {

    private static final String SUMMARY_MODEL = "gpt-4o-mini";
    private static final String SUMMARY_SYSTEM_PROMPT = """
            당신은 CBT 코칭 세션 분석 전문가입니다.
            사용자와 AI 캐릭터 간의 대화 내용을 바탕으로 세션 요약을 300~500자 이내로 작성하세요.

            요약 내용:
            - 사용자가 이야기한 주요 감정과 상황
            - 감지된 인지 왜곡 패턴 (있다면)
            - CBT 개입 여부 및 사용자 반응
            - 세션의 전반적인 톤과 진행 방향

            원칙:
            - 사용자 원문을 직접 인용하지 않습니다
            - 전문 용어보다 자연스러운 설명을 사용합니다
            - 개인 식별 정보는 포함하지 않습니다
            """;

    private final SessionRepository sessionRepository;
    private final SessionSummaryRepository sessionSummaryRepository;
    private final UserRepository userRepository;
    private final ThoughtRepository thoughtRepository;
    private final UserBeliefRepository beliefRepository;
    private final BeliefEvidenceAccumulator evidenceAccumulator;
    private final ExtractorLlmClient extractorLlmClient;
    private final LlmClient llmClient;
    private final MessageEncryptor messageEncryptor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // Phase 3-1 (PR #103) 병합 후 OntologyValidator 주입 예정
    // 현재는 ExtractorLLM 출력을 그대로 수용 (open validation)

    @Async
    @EventListener
    public void onSessionEnded(SessionEndedEvent event) {
        log.info("SessionConsolidator: processing sessionId={}", event.sessionId());
        try {
            consolidate(event.sessionId(), event.userId(), event.characterId());
        } catch (Exception e) {
            log.error("SessionConsolidator failed for sessionId={}", event.sessionId(), e);
        }
    }

    @Transactional
    public void consolidate(UUID sessionId, UUID userId, String characterId) {
        var session = sessionRepository.findById(sessionId).orElse(null);
        var user = userRepository.findById(userId).orElse(null);
        if (session == null || user == null) {
            log.warn("SessionConsolidator: session or user not found sessionId={}", sessionId);
            return;
        }

        // 1. 메시지 조회 (최근 20개 — working memory TTL 만료 대비)
        List<String> conversationLines = loadConversationLines(sessionId);
        if (conversationLines.isEmpty()) {
            log.info("SessionConsolidator: no messages found for sessionId={}", sessionId);
            return;
        }
        String conversationText = String.join("\n", conversationLines);

        // 2. 세션 요약 생성 (LLM)
        String summaryText = generateSummary(conversationText);

        // 3. AES-256 암호화
        byte[] ciphertext = messageEncryptor.encrypt(summaryText.getBytes(StandardCharsets.UTF_8));
        String dekId = messageEncryptor.dekId();

        // 4. ExtractorLLM — thought/emotion/trigger 추출
        ExtractorResult extracted = extractorLlmClient.extract(summaryText);

        // 5. OntologyValidator 필터 (Phase 3-1 의존, optional)
        List<ExtractorResult.ExtractedThought> validThoughts = filterValidThoughts(extracted.thoughts());
        String dominantEmotion = filterValidEmotion(extracted.dominantEmotion());

        // 6. session_summaries 저장/갱신
        upsertSessionSummary(session, user, characterId, summaryText, ciphertext, dekId,
                dominantEmotion, extracted.triggerTags(), extracted.episodeType());

        // 7. cbt_patterns 갱신
        for (ExtractorResult.ExtractedThought thought : validThoughts) {
            if (thought.distortionCode() != null) {
                upsertCbtPattern(userId, thought.distortionCode());
            }
        }

        // 8. emotional_states INSERT
        if (dominantEmotion != null) {
            insertEmotionalState(user, sessionId, dominantEmotion);
        }

        // 9. thoughts + UserBelief 연결
        for (ExtractorResult.ExtractedThought extracted_thought : validThoughts) {
            persistThought(user, sessionId, extracted_thought);
        }

        log.info("SessionConsolidator: completed sessionId={} thoughts={} emotion={}",
                sessionId, validThoughts.size(), dominantEmotion);
    }

    // ── 세션 메시지 로드 ─────────────────────────────────────────

    private List<String> loadConversationLines(UUID sessionId) {
        try {
            return jdbcTemplate.queryForList(
                    """
                    SELECT role || ': ' || content FROM messages
                    WHERE session_id = ? ORDER BY created_at ASC LIMIT 40
                    """,
                    String.class, sessionId
            );
        } catch (Exception e) {
            log.warn("Failed to load conversation for sessionId={}", sessionId, e);
            return List.of();
        }
    }

    // ── 요약 생성 ────────────────────────────────────────────────

    private String generateSummary(String conversationText) {
        StringBuilder sb = new StringBuilder();
        try {
            llmClient.stream(
                    LlmRequest.of(SUMMARY_MODEL, SUMMARY_SYSTEM_PROMPT, conversationText),
                    sb::append
            );
        } catch (Exception e) {
            log.warn("Summary generation failed, using fallback", e);
            return "세션 요약을 생성할 수 없습니다.";
        }
        return sb.toString().trim();
    }

    // ── session_summaries upsert ────────────────────────────────

    private void upsertSessionSummary(
            com.mio.session.domain.Session session,
            User user,
            String characterId,
            String summaryText,
            byte[] ciphertext,
            String dekId,
            String dominantEmotion,
            List<String> triggerTags,
            String episodeType) {

        String[] tagsArray = triggerTags.toArray(new String[0]);

        sessionSummaryRepository.findBySession_Id(session.getId()).ifPresentOrElse(
                existing -> {
                    jdbcTemplate.update(
                            """
                            UPDATE session_summaries
                            SET summary_text = ?, summary_ciphertext = ?, summary_dek_id = ?,
                                dominant_emotion = ?, trigger_tags = ?, episode_type = ?,
                                embedding_status = 'pending'
                            WHERE session_id = ?
                            """,
                            summaryText, ciphertext, dekId,
                            dominantEmotion, tagsArray, episodeType,
                            session.getId()
                    );
                },
                () -> {
                    SessionSummary summary = SessionSummary.builder()
                            .user(user)
                            .session(session)
                            .characterId(characterId)
                            .summaryText(summaryText)
                            .summaryCiphertext(ciphertext)
                            .summaryDekId(dekId)
                            .dominantEmotion(dominantEmotion)
                            .build();
                    sessionSummaryRepository.save(summary);
                    jdbcTemplate.update(
                            "UPDATE session_summaries SET trigger_tags = ?, episode_type = ? WHERE session_id = ?",
                            tagsArray, episodeType, session.getId()
                    );
                }
        );
    }

    // ── cbt_patterns upsert ──────────────────────────────────────

    private void upsertCbtPattern(UUID userId, String distortionCode) {
        jdbcTemplate.update(
                """
                INSERT INTO cbt_patterns (user_id, pattern_type, recurrence_count, session_occurrence_count, last_seen_at)
                VALUES (?, ?, 1, 1, now())
                ON CONFLICT (user_id, pattern_type) DO UPDATE
                SET recurrence_count = cbt_patterns.recurrence_count + 1,
                    session_occurrence_count = cbt_patterns.session_occurrence_count + 1,
                    last_seen_at = now()
                """,
                userId, distortionCode
        );
    }

    // ── emotional_states INSERT ───────────────────────────────────

    private void insertEmotionalState(User user, UUID sessionId, String emotionCode) {
        jdbcTemplate.update(
                """
                INSERT INTO emotional_states (user_id, source_event_id, primary_emotion, intensity, source)
                VALUES (?, ?, ?, 50, 'chat')
                """,
                user.getId(), sessionId, emotionCode
        );
    }

    // ── thoughts + belief 연결 ────────────────────────────────────

    private void persistThought(User user, UUID sessionId,
                                ExtractorResult.ExtractedThought extracted) {
        byte[] encryptedText = messageEncryptor.encrypt(
                extracted.thoughtText().getBytes(StandardCharsets.UTF_8)
        );

        Thought thought = Thought.builder()
                .user(user)
                .sessionId(sessionId)
                .thoughtTextCiphertext(encryptedText)
                .thoughtTextDekId(messageEncryptor.dekId())
                .distortionCode(extracted.distortionCode())
                .confidence(extracted.confidence())
                .build();
        thoughtRepository.save(thought);

        // UserBelief 연결 (new belief로 추가 또는 existing에 evidence 추가)
        if (extracted.beliefKind() != null) {
            addBeliefEvidence(user, sessionId, extracted);
        }
    }

    private void addBeliefEvidence(User user, UUID sessionId,
                                   ExtractorResult.ExtractedThought extracted) {
        List<UserBelief> existing = beliefRepository.findByUser_IdAndStatus(user.getId(), "active");
        // 동일 beliefKind + polarity의 기존 신념에 support/contradict 증거 추가
        // 없으면 새 belief 생성
        UserBelief belief = existing.stream()
                .filter(b -> b.getBeliefKind().equals(extracted.beliefKind())
                        && b.getPolarity() != null
                        && b.getPolarity().equals(extracted.polarity()))
                .findFirst()
                .orElseGet(() -> {
                    byte[] enc = messageEncryptor.encrypt(
                            extracted.thoughtText().getBytes(StandardCharsets.UTF_8));
                    UserBelief newBelief = UserBelief.builder()
                            .user(user)
                            .beliefTextCiphertext(enc)
                            .beliefTextDekId(messageEncryptor.dekId())
                            .beliefKind(extracted.beliefKind())
                            .polarity(extracted.polarity())
                            .build();
                    return beliefRepository.save(newBelief);
                });

        String evidenceKind = "negative".equals(extracted.polarity()) ? "support" : "contradict";
        evidenceAccumulator.accumulate(belief, evidenceKind, sessionId, null);
    }

    // ── OntologyValidator 필터 (Phase 3-1 병합 후 적용) ─────────────

    private List<ExtractorResult.ExtractedThought> filterValidThoughts(
            List<ExtractorResult.ExtractedThought> thoughts) {
        // TODO(Phase 3-1): OntologyValidator.filterValidDistortionCodes() 적용
        return thoughts;
    }

    private String filterValidEmotion(String emotionCode) {
        // TODO(Phase 3-1): OntologyValidator.isValidEmotionCode() 적용
        return emotionCode;
    }
}
