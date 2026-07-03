package com.mio.ai.memory.consolidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.domain.CbtPattern;
import com.mio.ai.domain.EmotionalState;
import com.mio.ai.domain.MemoryEmbedding;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.memory.ontology.OntologyValidator;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.memory.episodic.Thought;
import com.mio.ai.memory.episodic.ThoughtRepository;
import com.mio.ai.memory.episodic.UserBelief;
import com.mio.ai.memory.episodic.UserBeliefRepository;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.session.domain.SessionCheckpoint;
import com.mio.session.domain.SessionSummary;
import com.mio.session.repository.SessionCheckpointRepository;
import com.mio.session.repository.SessionRepository;
import com.mio.session.repository.SessionSummaryRepository;
import com.mio.user.domain.User;
import com.mio.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final SessionCheckpointRepository checkpointRepository;
    private final UserRepository userRepository;
    private final ThoughtRepository thoughtRepository;
    private final UserBeliefRepository beliefRepository;
    private final BeliefEvidenceAccumulator evidenceAccumulator;
    private final ExtractorLlmClient extractorLlmClient;
    private final LlmClient llmClient;
    private final MessageEncryptor messageEncryptor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final OntologyValidator ontologyValidator;
    private final TodoRecommendationService todoRecommendationService;
    private final SummaryStatusWriter summaryStatusWriter;
    // 메모리 보강을 별도 트랜잭션(REQUIRES_NEW)으로 호출하기 위한 self 프록시.
    // self-invocation으로는 프록시 어드바이스(@Transactional)가 적용되지 않으므로 ObjectProvider로 우회.
    private final ObjectProvider<SessionConsolidator> self;

    // belief_kind / polarity DB CHECK 제약과 동일한 허용값 화이트리스트.
    // ExtractorLLM이 문자열 "null"이나 시드 밖 환각값을 반환해도 DB 위반 없이 걸러내기 위함.
    private static final Set<String> VALID_BELIEF_KINDS = Set.of(
            "core_self", "core_other", "core_world", "intermediate_rule", "compensatory_strategy");
    private static final Set<String> VALID_POLARITIES = Set.of("positive", "negative", "neutral");

    // @TransactionalEventListener(AFTER_COMMIT): endSession 트랜잭션 커밋 후 실행 → 커밋된 데이터 안전하게 읽기
    // 진입점 자체에는 @Transactional을 두지 않는다. 1·2단계를 각각 self 프록시 + REQUIRES_NEW로
    // 호출해, 요약 트랜잭션이 먼저 커밋된 뒤 메모리 보강 트랜잭션이 독립적으로 실행되게 한다.
    // summary_status=DONE은 사용자 응답에 필요한 Todo 저장까지 끝난 뒤 별도로 표시한다.
    // (진입점을 @Transactional로 두면 요약 tx가 커밋되지 않은 채 보강 tx가 suspend 상태로 겹쳐
    //  커넥션 동시 점유·가시성 문제가 생긴다.)
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionEnded(SessionEndedEvent event) {
        log.info("SessionConsolidator: processing sessionId={}", event.sessionId());
        EnrichmentInput enrichInput;
        try {
            // 1단계: 세션 요약을 독립 트랜잭션(REQUIRES_NEW)에서 영속화한다.
            // self 프록시로 호출해야 consolidate의 @Transactional 어드바이스가 적용된다.
            enrichInput = self.getObject().consolidate(
                    event.sessionId(), event.userId(), event.characterId(), event.socraticCount());
        } catch (Exception e) {
            log.error("SessionConsolidator failed for sessionId={}", event.sessionId(), e);
            // REQUIRES_NEW: 현재 트랜잭션이 rollback-only 또는 DB-aborted 상태일 수 있으므로
            // 별도 트랜잭션에서 failed 상태를 저장한다.
            summaryStatusWriter.markFailed(event.sessionId());
            return;
        }

        // 2단계: thoughts/beliefs/cbt_patterns/todos 등 메모리 보강은 별도 트랜잭션(REQUIRES_NEW)에서
        // best-effort로 실행한다. 1단계 요약은 이미 커밋되었으므로 보강 실패가 요약에 영향을 주지 않는다.
        if (enrichInput != null) {
            try {
                self.getObject().enrichMemory(enrichInput);
            } catch (Exception e) {
                log.error("SessionConsolidator: memory enrichment failed but summary preserved sessionId={}",
                        event.sessionId(), e);
            }

            // 3단계: Todo 자동 생성 (MIO-CBT-015, 세션 맥락 개인화 — 이슈 #228).
            // 블로킹 LLM 개인화 호출이 DB 트랜잭션 밖에서 실행되도록 enrichMemory 커밋 후 별도로 호출한다.
            int generatedTodoCount;
            try {
                generatedTodoCount = todoRecommendationService.generateForSession(
                        enrichInput.userId(), enrichInput.sessionId(),
                        new TodoRecommendationService.TodoGenerationInput(
                                enrichInput.distortionCodes(), enrichInput.dominantEmotion(),
                                enrichInput.triggerTags(), enrichInput.summaryText()));
            } catch (Exception e) {
                log.warn("SessionConsolidator: todo generation failed sessionId={}", event.sessionId(), e);
                summaryStatusWriter.markFailed(event.sessionId());
                return;
            }
            if (generatedTodoCount <= 0) {
                log.warn("SessionConsolidator: no todo generated; summary not exposed sessionId={}",
                        event.sessionId());
                summaryStatusWriter.markFailed(event.sessionId());
                return;
            }
            summaryStatusWriter.markDone(event.sessionId());
        }
    }

    /**
     * 1단계: 세션 요약을 생성·영속화한다(독립 트랜잭션).
     * 메모리 보강에 필요한 입력을 반환하며, 영속화할 요약이 없으면(세션/유저 부재·메시지 없음)
     * 상태를 변경하지 않고 null을 반환한다. (요약 row 없이 DONE으로 표시되어 조회 시 404가 나는 것을 방지)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EnrichmentInput consolidate(UUID sessionId, UUID userId, String characterId, int socraticCount) {
        var session = sessionRepository.findById(sessionId).orElse(null);
        var user = userRepository.findById(userId).orElse(null);
        if (session == null || user == null) {
            log.warn("SessionConsolidator: session or user not found sessionId={}", sessionId);
            return null;
        }

        // 1. 대화 컨텍스트 구성 (체크포인트 요약 + 잔여 메시지)
        String conversationText = buildConversationContext(sessionId);
        if (conversationText.isBlank()) {
            log.info("SessionConsolidator: no messages found for sessionId={}", sessionId);
            return null;
        }

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

        // 6. session_summaries 저장/갱신 (CBT 필드 포함)
        String biasTypesJson = toJson(
                validThoughts.stream()
                        .map(ExtractorResult.ExtractedThought::distortionCode)
                        .filter(code -> code != null && !code.isBlank())
                        .distinct()
                        .toList());
        String keyThoughtsJson = toJson(
                validThoughts.stream()
                        .filter(t -> t.thoughtText() != null && !t.thoughtText().isBlank())
                        .map(t -> {
                            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                            m.put("content", t.thoughtText());
                            m.put("distortion_type", t.distortionCode());
                            return m;
                        })
                        .toList());
        boolean cbtIntervened = "cbt_success".equalsIgnoreCase(extracted.episodeType())
                || "cbt_partial".equalsIgnoreCase(extracted.episodeType());

        upsertSessionSummary(session, user, characterId, summaryText, ciphertext, dekId,
                dominantEmotion, extracted.triggerTags(), extracted.episodeType(),
                biasTypesJson, keyThoughtsJson, cbtIntervened, socraticCount);

        // emotion_score_ai: AI 추정 세션 감정 점수 (0~100) 저장
        if (extracted.emotionScore() != null) {
            jdbcTemplate.update(
                    "UPDATE sessions SET emotion_score_ai = ? WHERE id = ?",
                    extracted.emotionScore(), sessionId
            );
        }

        List<String> distortionCodes = validThoughts.stream()
                .map(ExtractorResult.ExtractedThought::distortionCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();

        log.info("SessionConsolidator: summary persisted sessionId={} episodeType={} cbtIntervened={} thoughts={} emotion={}",
                sessionId, extracted.episodeType(), cbtIntervened, validThoughts.size(), dominantEmotion);

        return new EnrichmentInput(userId, sessionId, validThoughts, dominantEmotion, distortionCodes,
                extracted.triggerTags(), summaryText);
    }

    /**
     * 2단계: 메모리 보강(cbt_patterns / emotional_states / thoughts·beliefs)을
     * 요약과 분리된 별도 트랜잭션으로 실행한다. 이 단계의 실패는 요약 영속화에 영향을 주지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enrichMemory(EnrichmentInput in) {
        var user = userRepository.findById(in.userId()).orElse(null);
        var session = sessionRepository.findById(in.sessionId()).orElse(null);
        if (user == null || session == null) {
            log.warn("SessionConsolidator: enrich skipped — session or user not found sessionId={}", in.sessionId());
            return;
        }
        UUID sessionId = in.sessionId();

        // 7. cbt_patterns 갱신 (세션 내 동일 왜곡 중복 방지 — distinct codes만 처리)
        in.distortionCodes().forEach(code -> upsertCbtPattern(in.userId(), code));

        // 8. emotional_states INSERT
        if (in.dominantEmotion() != null) {
            insertEmotionalState(user, sessionId, in.dominantEmotion());
        }

        // 9. thoughts + UserBelief 연결
        for (ExtractorResult.ExtractedThought extractedThought : in.validThoughts()) {
            persistThought(user, sessionId, extractedThought);
        }

        // Todo 자동 생성은 블로킹 LLM 개인화 호출을 포함하므로 이 트랜잭션 안에서 하지 않는다.
        // onSessionEnded에서 이 트랜잭션 커밋 이후 별도로 호출한다(이슈 #228).

        log.info("SessionConsolidator: enrichment completed sessionId={} thoughts={} emotion={}",
                sessionId, in.validThoughts().size(), in.dominantEmotion());
    }

    /** 2단계 메모리 보강에 필요한 입력 (요약 트랜잭션 종료 후 별도 트랜잭션으로 전달). */
    public record EnrichmentInput(
            UUID userId,
            UUID sessionId,
            List<ExtractorResult.ExtractedThought> validThoughts,
            String dominantEmotion,
            List<String> distortionCodes,
            List<String> triggerTags,
            String summaryText
    ) {}

    // ── 대화 컨텍스트 구성 ────────────────────────────────────────

    /**
     * 체크포인트 요약 + 마지막 체크포인트 이후 잔여 메시지를 합쳐 반환한다.
     * 체크포인트가 없으면 전체 메시지를 그대로 반환한다 (기존 동작).
     */
    private String buildConversationContext(UUID sessionId) {
        List<SessionCheckpoint> checkpoints =
                checkpointRepository.findBySession_IdOrderByCheckpointSeqAsc(sessionId);

        if (checkpoints.isEmpty()) {
            return String.join("\n", loadConversationLines(sessionId));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 이전 대화 요약 ===\n");
        for (SessionCheckpoint cp : checkpoints) {
            sb.append("[중간 요약 ").append(cp.getCheckpointSeq()).append("]\n");
            sb.append(cp.getSummaryText()).append("\n\n");
        }

        SessionCheckpoint latest = checkpoints.getLast();
        List<String> recentLines = loadMessageLinesSince(sessionId, latest.getCoveredUpToAt());
        if (!recentLines.isEmpty()) {
            sb.append("=== 최근 대화 ===\n");
            sb.append(String.join("\n", recentLines));
        }

        return sb.toString().trim();
    }

    // ── 세션 메시지 로드 ─────────────────────────────────────────

    private List<String> loadConversationLines(UUID sessionId) {
        return loadMessageLinesSince(sessionId, null);
    }

    private List<String> loadMessageLinesSince(UUID sessionId, java.time.OffsetDateTime since) {
        try {
            List<java.util.Map<String, Object>> rows;
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
            for (var row : rows) {
                String role = (String) row.get("role");
                byte[] cipher = (byte[]) row.get("content_ciphertext");
                if (cipher == null) continue;
                try {
                    String text = new String(messageEncryptor.decrypt(cipher), StandardCharsets.UTF_8);
                    lines.add(role + ": " + text);
                } catch (Exception decryptEx) {
                    log.debug("Skipping message decrypt failure in session={}", sessionId);
                }
            }
            return lines;
        } catch (Exception e) {
            log.warn("Failed to load conversation for sessionId={}", sessionId, e);
            return List.of();
        }
    }

    // ── 요약 생성 ────────────────────────────────────────────────

    private String generateSummary(String conversationText) {
        StringBuilder sb = new StringBuilder();
        llmClient.stream(
                LlmRequest.of(SUMMARY_MODEL, SUMMARY_SYSTEM_PROMPT, conversationText),
                sb::append
        );
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
            String episodeType,
            String biasTypesJson,
            String keyThoughtsJson,
            boolean cbtIntervened,
            int socraticCount) {

        String[] tagsArray = triggerTags.toArray(new String[0]);

        sessionSummaryRepository.findBySession_Id(session.getId()).ifPresentOrElse(
                existing -> {
                    jdbcTemplate.update(
                            """
                            UPDATE session_summaries
                            SET summary_text = ?, summary_ciphertext = ?, summary_dek_id = ?,
                                dominant_emotion = ?, trigger_tags = ?, episode_type = ?,
                                bias_types_detected = ?::jsonb, cbt_intervened = ?, key_thoughts = ?::jsonb,
                                socratic_count = ?, embedding_status = 'pending'
                            WHERE session_id = ?
                            """,
                            summaryText, ciphertext, dekId,
                            dominantEmotion, tagsArray, episodeType,
                            biasTypesJson, cbtIntervened, keyThoughtsJson,
                            socraticCount, session.getId()
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
                            .biasTypesDetected(biasTypesJson)
                            .cbtIntervened(cbtIntervened)
                            .keyThoughts(keyThoughtsJson)
                            .socraticCount(socraticCount)
                            .build();
                    // saveAndFlush: JPA flush 후 jdbcTemplate.update가 실제 row를 찾도록 보장
                    sessionSummaryRepository.saveAndFlush(summary);
                    jdbcTemplate.update(
                            "UPDATE session_summaries SET trigger_tags = ?, episode_type = ? WHERE session_id = ?",
                            tagsArray, episodeType, session.getId()
                    );
                }
        );
    }

    // ── JSON 직렬화 ───────────────────────────────────────────────

    private String toJson(List<?> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("SessionConsolidator: JSON serialization failed", e);
            return "[]";
        }
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
        // ExtractorLLM이 문자열 "null"·시드 밖 환각값을 반환할 수 있으므로 DB CHECK 허용값으로 검증한다.
        // !=null 만으로는 문자열 "null"을 거르지 못해 user_beliefs CHECK(23514) 위반을 유발한다.
        String beliefKind = extracted.beliefKind();
        if (beliefKind != null && !VALID_BELIEF_KINDS.contains(beliefKind)) {
            log.warn("SessionConsolidator: discarded unknown beliefKind='{}' sessionId={}", beliefKind, sessionId);
            beliefKind = null;
        }
        if (beliefKind != null) {
            addBeliefEvidence(user, sessionId, extracted, beliefKind);
        }
    }

    private void addBeliefEvidence(User user, UUID sessionId,
                                   ExtractorResult.ExtractedThought extracted, String beliefKind) {
        // polarity도 DB CHECK(IN positive/negative/neutral) 대상 — 허용값 밖이면 null(컬럼 nullable)로 정규화.
        String polarity = VALID_POLARITIES.contains(extracted.polarity()) ? extracted.polarity() : null;

        List<UserBelief> existing = beliefRepository.findByUser_IdAndStatus(user.getId(), "active");
        // 동일 beliefKind + polarity의 기존 신념에 support/contradict 증거 추가
        // 없으면 새 belief 생성
        // polarity가 둘 다 null이어도 동일 신념으로 매칭한다(중복 belief 생성 방지).
        UserBelief belief = existing.stream()
                .filter(b -> b.getBeliefKind().equals(beliefKind)
                        && java.util.Objects.equals(b.getPolarity(), polarity))
                .findFirst()
                .orElseGet(() -> {
                    byte[] enc = messageEncryptor.encrypt(
                            extracted.thoughtText().getBytes(StandardCharsets.UTF_8));
                    UserBelief newBelief = UserBelief.builder()
                            .user(user)
                            .beliefTextCiphertext(enc)
                            .beliefTextDekId(messageEncryptor.dekId())
                            .beliefKind(beliefKind)
                            .polarity(polarity)
                            .build();
                    return beliefRepository.save(newBelief);
                });

        String evidenceKind = "negative".equals(polarity) ? "support" : "contradict";
        evidenceAccumulator.accumulate(belief, evidenceKind, sessionId, null);
    }

    // ── OntologyValidator 필터 ────────────────────────────────────

    private List<ExtractorResult.ExtractedThought> filterValidThoughts(
            List<ExtractorResult.ExtractedThought> thoughts) {
        if (thoughts == null || thoughts.isEmpty()) return List.of();

        // 배치 검증: 중복 제거 후 단일 IN 쿼리로 검증 (N+1 방지, §12.8)
        Set<String> distinctCodes = thoughts.stream()
                .map(ExtractorResult.ExtractedThought::distortionCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());

        Set<String> validCodes = ontologyValidator.filterValidDistortionCodes(distinctCodes);

        Set<String> invalidCodes = distinctCodes.stream()
                .filter(code -> !validCodes.contains(code))
                .collect(Collectors.toSet());
        if (!invalidCodes.isEmpty()) {
            log.warn("SessionConsolidator: discarded unknown distortionCodes={}", invalidCodes);
        }

        var result = thoughts.stream()
                .filter(t -> t.distortionCode() == null || validCodes.contains(t.distortionCode()))
                .toList();

        int discarded = thoughts.size() - result.size();
        if (discarded > 0) {
            log.info("SessionConsolidator: OntologyValidator filtered {}/{} thoughts", discarded, thoughts.size());
        }
        return result;
    }

    private String filterValidEmotion(String emotionCode) {
        if (emotionCode == null) return null;
        if (ontologyValidator.isValidEmotionCode(emotionCode)) return emotionCode;
        log.warn("SessionConsolidator: discarded unknown emotionCode='{}'", emotionCode);
        return null;
    }
}
