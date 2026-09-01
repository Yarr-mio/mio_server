package com.mio.memorycontrol.service;

import com.mio.ai.memory.composer.ContextComposer;
import com.mio.ai.memory.retrieval.LexicalRetriever;
import com.mio.ai.memory.retrieval.RetrievedItem;
import com.mio.ai.memory.retrieval.StructuredRetriever;
import com.mio.ai.memory.retrieval.VectorRetriever;
import com.mio.common.crypto.MessageEncryptor;
import com.mio.common.error.BusinessException;
import com.mio.common.error.ErrorCode;
import com.mio.memorycontrol.dto.MemoryConsentWithdrawResponse;
import com.mio.memorycontrol.dto.MemoryItemResponse;
import com.mio.memorycontrol.dto.MemoryListResponse;
import com.mio.memorycontrol.dto.MemoryUpdateRequest;
import com.mio.memorycontrol.dto.MemoryUpdateResponse;
import com.mio.support.MioIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 메모리 조회·정정·동의 철회의 완료 조건 검증 (이슈 #453, 로드맵 §12 P0-6).
 *
 * <p>핵심 완료 조건: 정정·비활성화한 기억이 벡터·어휘·구조 검색 <b>어디서도</b> 회수되지
 * 않아야 하고(회수율 0), 동의 철회 후 기존 기억이 전부 비활성화돼야 한다. 검색기가 하나라도
 * 필터를 빼먹으면 사용자가 지운 기억이 다음 턴 프롬프트에 되살아난다.
 */
@MioIntegrationTest
class MemoryControlIntegrationTest {

    private static final String SUMMARY_TEXT = "프로젝트 발표를 망쳤다고 걱정함. 파국화 패턴이 관찰됨.";
    private static final String THOUGHT_TEXT = "나는 항상 실패한다";
    private static final String BELIEF_TEXT = "나는 부족한 사람이다";
    private static final String TRIGGER_TAG = "work_stress";
    private static final String DISTORTION_CODE = "catastrophizing";

    @Autowired private MemoryControlService memoryControlService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MessageEncryptor messageEncryptor;
    @Autowired private VectorRetriever vectorRetriever;
    @Autowired private LexicalRetriever lexicalRetriever;
    @Autowired private StructuredRetriever structuredRetriever;
    @Autowired private ContextComposer contextComposer;

    private UUID userId;
    private UUID sessionId;
    private UUID summaryId;
    private UUID thoughtId;
    private UUID beliefId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        summaryId = UUID.randomUUID();
        thoughtId = UUID.randomUUID();
        beliefId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                userId, "memctl-it-" + userId);
        jdbcTemplate.update(
                "INSERT INTO sessions (id, user_id, character_id, status, ended_at) VALUES (?, ?, 'mio', 'ended', now())",
                sessionId, userId);
        jdbcTemplate.update(
                """
                INSERT INTO session_summaries
                    (id, user_id, session_id, character_id, summary_text, embedding_status,
                     trigger_tags, episode_emb, created_at)
                VALUES (?, ?, ?, 'mio', ?, 'done',
                        ARRAY[?]::text[], array_fill(0.1::real, ARRAY[1536])::vector, now() - interval '2 hours')
                """,
                summaryId, userId, sessionId, SUMMARY_TEXT, TRIGGER_TAG);
        jdbcTemplate.update(
                """
                INSERT INTO thoughts
                    (id, user_id, session_id, thought_text_ciphertext, thought_text_dek_id,
                     distortion_code, confidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0.9, now() - interval '1 hour')
                """,
                thoughtId, userId, sessionId, encrypt(THOUGHT_TEXT), messageEncryptor.dekId(), DISTORTION_CODE);
        jdbcTemplate.update(
                """
                INSERT INTO user_beliefs
                    (id, user_id, belief_text_ciphertext, belief_text_dek_id, belief_kind, polarity,
                     confidence, status, created_at)
                VALUES (?, ?, ?, ?, 'core_self', 'negative', 0.9, 'active', now())
                """,
                beliefId, userId, encrypt(BELIEF_TEXT), messageEncryptor.dekId());
    }

    // ── 조회 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("사용자는 요약·에피소드·신념을 출처·생성일과 함께 복호화된 본문으로 조회한다")
    void listMemories_returnsAllTypesWithDecryptedContentAndProvenance() {
        MemoryListResponse response = memoryControlService.listMemories(userId, 0, 20);

        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.items()).hasSize(3);
        assertThat(response.items()).extracting(MemoryItemResponse::type)
                .containsExactlyInAnyOrder("summary", "episode", "belief");
        assertThat(response.items()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("active");
            assertThat(item.createdAt()).isNotNull();
            assertThat(item.source()).isNotBlank();
        });

        MemoryItemResponse summary = itemOf(response, "summary");
        MemoryItemResponse episode = itemOf(response, "episode");
        MemoryItemResponse belief = itemOf(response, "belief");
        assertThat(summary.content()).isEqualTo(SUMMARY_TEXT);
        assertThat(summary.sessionId()).isEqualTo(sessionId);
        assertThat(episode.content()).isEqualTo(THOUGHT_TEXT);
        assertThat(belief.content()).isEqualTo(BELIEF_TEXT);

        // 생성일 내림차순 — 가장 최근 기억이 먼저 보인다
        assertThat(response.items().getFirst().type()).isEqualTo("belief");

        Integer viewAudit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action = 'memory_view'",
                Integer.class, userId);
        assertThat(viewAudit).isPositive();
    }

    @Test
    @DisplayName("페이지네이션 — size만큼 자르고 전체 건수를 함께 준다")
    void listMemories_paginates() {
        MemoryListResponse first = memoryControlService.listMemories(userId, 0, 2);
        MemoryListResponse second = memoryControlService.listMemories(userId, 1, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(1);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(second.totalElements()).isEqualTo(3);

        // 범위 밖 페이지도 총 건수를 잃지 않는다 (COUNT(*) OVER() 폴백 경로)
        MemoryListResponse beyond = memoryControlService.listMemories(userId, 5, 2);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("created_at 동률 행은 id 타이브레이커로 페이지 간 중복·누락 없이 나뉜다")
    void listMemories_tieBreaksByIdAcrossPages() {
        // 기존 3건과 겹치지 않는 정확히 같은 시각의 에피소드 4건
        for (int i = 0; i < 4; i++) {
            jdbcTemplate.update(
                    """
                    INSERT INTO thoughts
                        (id, user_id, session_id, thought_text_ciphertext, thought_text_dek_id,
                         distortion_code, confidence, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 0.5, TIMESTAMPTZ '2026-08-10 12:00:00+00')
                    """,
                    UUID.randomUUID(), userId, sessionId, encrypt("동시각 사고 " + i),
                    messageEncryptor.dekId(), DISTORTION_CODE);
        }

        java.util.List<UUID> collected = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            memoryControlService.listMemories(userId, page, 2).items()
                    .forEach(item -> collected.add(item.id()));
        }

        assertThat(collected).hasSize(7);
        assertThat(collected).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("페이지 인자 경계를 벗어나면 거부한다 (page<0, size=0, size>100)")
    void listMemories_rejectsOutOfRangePaging() {
        for (int[] paging : new int[][]{{-1, 20}, {0, 0}, {0, 101}}) {
            assertThatThrownBy(() -> memoryControlService.listMemories(userId, paging[0], paging[1]))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("복호화에 실패한 항목은 본문만 비운 채 목록에 남는다")
    void listMemories_keepsRowWithNullContentOnDecryptFailure() {
        UUID corruptId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO thoughts
                    (id, user_id, session_id, thought_text_ciphertext, thought_text_dek_id,
                     distortion_code, confidence)
                VALUES (?, ?, ?, ?, ?, ?, 0.5)
                """,
                corruptId, userId, sessionId, new byte[]{1, 2, 3}, messageEncryptor.dekId(),
                DISTORTION_CODE);

        MemoryListResponse response = memoryControlService.listMemories(userId, 0, 20);

        MemoryItemResponse corrupt = response.items().stream()
                .filter(item -> item.id().equals(corruptId))
                .findFirst().orElseThrow();
        assertThat(corrupt.content()).isNull();
        assertThat(corrupt.status()).isEqualTo("active");
        assertThat(response.totalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("비활성 기억이 벡터 상위 후보를 채워도 over-fetch 로 활성 기억을 회수한다")
    void vectorRetrieval_overFetchesPastDisabledCandidates() {
        // 활성 요약 1건(기존) + 비활성 요약 5건 — 전부 동일 임베딩이라 KNN 순위가 무의미하게 섞인다
        for (int i = 0; i < 5; i++) {
            UUID extraSession = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO sessions (id, user_id, character_id, status, ended_at) VALUES (?, ?, 'mio', 'ended', now())",
                    extraSession, userId);
            jdbcTemplate.update(
                    """
                    INSERT INTO session_summaries
                        (id, user_id, session_id, character_id, summary_text, embedding_status,
                         episode_emb, memory_status)
                    VALUES (?, ?, ?, 'mio', ?, 'done',
                            array_fill(0.1::real, ARRAY[1536])::vector, 'disabled')
                    """,
                    UUID.randomUUID(), userId, extraSession, "비활성 요약 " + i);
        }

        var retrieved = vectorRetriever.retrieveEpisodes(userId, uniformEmbedding(), 3);

        assertThat(retrieved).hasSize(1);
        assertThat(retrieved.getFirst().content()).isEqualTo(SUMMARY_TEXT);
    }

    // ── 정정 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("정정한 요약은 벡터·어휘·트리거·왜곡그래프 검색 어디서도 회수되지 않는다")
    void correctedSummary_isExcludedFromAllRetrievalSources() {
        MemoryUpdateResponse updated = memoryControlService.updateMemory(userId, summaryId,
                new MemoryUpdateRequest("correct", "발표는 사실 무사히 끝났다"));

        assertThat(updated.status()).isEqualTo("corrected");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT memory_status FROM session_summaries WHERE id = ?", String.class, summaryId))
                .isEqualTo("corrected");

        assertThat(vectorRetriever.retrieveEpisodes(userId, uniformEmbedding(), 5)).isEmpty();
        assertThat(lexicalRetriever.retrieveByKeywords(userId, "프로젝트", 5)).isEmpty();
        assertThat(structuredRetriever.retrieveTriggers(userId, List.of(TRIGGER_TAG))).isEmpty();
        assertThat(structuredRetriever.retrieveRelatedDistortionEpisodes(
                userId, Set.of(DISTORTION_CODE))).isEmpty();

        // 정정 원문은 암호화되어 이력으로 남는다
        byte[] ciphertext = jdbcTemplate.queryForObject(
                "SELECT corrected_text_ciphertext FROM user_memory_corrections WHERE memory_id = ?",
                byte[].class, summaryId);
        assertThat(new String(messageEncryptor.decrypt(ciphertext), StandardCharsets.UTF_8))
                .isEqualTo("발표는 사실 무사히 끝났다");

        // 다음 턴 조회에서 정정문이 함께 보인다
        MemoryItemResponse summary = itemOf(memoryControlService.listMemories(userId, 0, 20), "summary");
        assertThat(summary.correctedText()).isEqualTo("발표는 사실 무사히 끝났다");

        assertAudit("memory_correct", summaryId.toString());
    }

    @Test
    @DisplayName("정정·비활성 기억은 프롬프트 컨텍스트 조합에 주입되지 않는다")
    void correctedMemory_isNotComposedIntoPromptContext() {
        memoryControlService.updateMemory(userId, summaryId,
                new MemoryUpdateRequest("correct", "발표는 사실 무사히 끝났다"));
        memoryControlService.updateMemory(userId, beliefId, new MemoryUpdateRequest("disable", null));

        List<RetrievedItem> all = new ArrayList<>();
        all.addAll(vectorRetriever.retrieveEpisodes(userId, uniformEmbedding(), 5));
        all.addAll(vectorRetriever.retrieveBeliefs(userId, null, 5));
        all.addAll(lexicalRetriever.retrieveByKeywords(userId, "프로젝트", 5));
        all.addAll(structuredRetriever.retrieveProfile(userId));
        all.addAll(structuredRetriever.retrieveTriggers(userId, List.of(TRIGGER_TAG)));
        all.addAll(structuredRetriever.retrieveRelatedDistortionEpisodes(userId, Set.of(DISTORTION_CODE)));
        all.addAll(structuredRetriever.retrieveBeliefNeighbors(userId, Set.of(beliefId.toString())));

        String context = contextComposer.compose(all, "restricted", false);
        assertThat(context).doesNotContain(SUMMARY_TEXT);
        assertThat(context).doesNotContain("core_self");
    }

    // ── 비활성화 ─────────────────────────────────────────────────

    @Test
    @DisplayName("비활성화한 신념은 프로필·신념 검색에서 회수되지 않는다")
    void disabledBelief_isExcludedFromBeliefRetrieval() {
        MemoryUpdateResponse updated = memoryControlService.updateMemory(
                userId, beliefId, new MemoryUpdateRequest("disable", null));

        assertThat(updated.status()).isEqualTo("disabled");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM user_beliefs WHERE id = ?", String.class, beliefId))
                .isEqualTo("disabled");

        assertThat(vectorRetriever.retrieveBeliefs(userId, null, 5)).isEmpty();
        assertThat(structuredRetriever.retrieveProfile(userId)).isEmpty();
        assertThat(structuredRetriever.retrieveBeliefNeighbors(userId, Set.of(beliefId.toString()))).isEmpty();

        assertAudit("memory_disable", beliefId.toString());
    }

    @Test
    @DisplayName("비활성화한 에피소드는 왜곡 그래프 검색에서 회수되지 않는다")
    void disabledEpisode_isExcludedFromDistortionGraphRetrieval() {
        memoryControlService.updateMemory(userId, thoughtId, new MemoryUpdateRequest("disable", null));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT memory_status FROM thoughts WHERE id = ?", String.class, thoughtId))
                .isEqualTo("disabled");
        assertThat(structuredRetriever.retrieveRelatedDistortionEpisodes(
                userId, Set.of(DISTORTION_CODE))).isEmpty();
    }

    // ── 입력 검증·소유권 ─────────────────────────────────────────

    @Test
    @DisplayName("남의 기억·없는 기억·잘못된 action은 거부한다")
    void updateMemory_rejectsInvalidInput() {
        assertThatThrownBy(() -> memoryControlService.updateMemory(
                userId, UUID.randomUUID(), new MemoryUpdateRequest("disable", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMORY_NOT_FOUND);

        UUID otherUser = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, social_provider, social_id) VALUES (?, 'kakao', ?)",
                otherUser, "memctl-other-" + otherUser);
        assertThatThrownBy(() -> memoryControlService.updateMemory(
                otherUser, summaryId, new MemoryUpdateRequest("disable", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMORY_NOT_FOUND);

        assertThatThrownBy(() -> memoryControlService.updateMemory(
                userId, summaryId, new MemoryUpdateRequest("erase", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEMORY_ACTION);

        assertThatThrownBy(() -> memoryControlService.updateMemory(
                userId, summaryId, new MemoryUpdateRequest("correct", "  ")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEMORY_CORRECTION_TEXT_REQUIRED);
    }

    // ── 동의 철회 ─────────────────────────────────────────────────

    @Test
    @DisplayName("동의 철회는 기존 기억을 전부 비활성화하고 검색 회수율을 0으로 만든다")
    void withdrawConsent_disablesAllExistingMemories() {
        MemoryConsentWithdrawResponse response = memoryControlService.withdrawConsent(userId);

        assertThat(response.withdrawnAt()).isNotNull();
        assertThat(response.disabledCount()).isEqualTo(3);

        Boolean agreed = jdbcTemplate.queryForObject(
                "SELECT memory_retention_agreed FROM user_memory_preferences WHERE user_id = ?",
                Boolean.class, userId);
        assertThat(agreed).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT memory_status FROM session_summaries WHERE id = ?", String.class, summaryId))
                .isEqualTo("disabled");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT memory_status FROM thoughts WHERE id = ?", String.class, thoughtId))
                .isEqualTo("disabled");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM user_beliefs WHERE id = ?", String.class, beliefId))
                .isEqualTo("disabled");

        assertThat(vectorRetriever.retrieveEpisodes(userId, uniformEmbedding(), 5)).isEmpty();
        assertThat(vectorRetriever.retrieveBeliefs(userId, null, 5)).isEmpty();
        assertThat(lexicalRetriever.retrieveByKeywords(userId, "프로젝트", 5)).isEmpty();
        assertThat(structuredRetriever.retrieveProfile(userId)).isEmpty();
        assertThat(structuredRetriever.retrieveTriggers(userId, List.of(TRIGGER_TAG))).isEmpty();

        // 철회 후에도 사용자는 자신의 기억 목록을 볼 수 있다 (상태만 disabled)
        MemoryListResponse list = memoryControlService.listMemories(userId, 0, 20);
        assertThat(list.items()).hasSize(3);
        assertThat(list.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo("disabled"));

        assertAudit("memory_consent_withdraw", userId.toString());
    }

    @Test
    @DisplayName("동의 철회는 멱등이다 — 재호출해도 최초 철회 시각이 유지된다")
    void withdrawConsent_isIdempotent() {
        MemoryConsentWithdrawResponse first = memoryControlService.withdrawConsent(userId);
        MemoryConsentWithdrawResponse second = memoryControlService.withdrawConsent(userId);

        assertThat(second.withdrawnAt()).isEqualTo(first.withdrawnAt());
        assertThat(second.disabledCount()).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────

    private byte[] encrypt(String text) {
        return messageEncryptor.encrypt(text.getBytes(StandardCharsets.UTF_8));
    }

    private float[] uniformEmbedding() {
        float[] embedding = new float[1536];
        java.util.Arrays.fill(embedding, 0.1f);
        return embedding;
    }

    private MemoryItemResponse itemOf(MemoryListResponse response, String type) {
        return response.items().stream()
                .filter(item -> item.type().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("type not found: " + type));
    }

    private void assertAudit(String action, String resourceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action = ? AND resource_id = ?",
                Integer.class, userId, action, resourceId);
        assertThat(count).isPositive();
    }
}
