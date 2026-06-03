package com.mio.checkin.service;

import com.mio.ai.llm.GeminiLlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.checkin.repository.CheckinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 체크인 완료 후 Gemini 2.0 Flash로 AI 공감 코멘트 생성 → checkins.ai_response 저장.
 * 비동기 실행. 실패해도 체크인 자체에 영향 없음 (fail-open).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckinAiResponseGenerator {

    private static final String SYSTEM_PROMPT = """
            당신은 감정 코칭 AI 캐릭터입니다.
            사용자의 현재 감정 상태와 강도를 바탕으로 따뜻하고 공감적인 짧은 코멘트를 작성하세요.

            원칙:
            - 100~150자 이내로 작성합니다
            - 감정을 먼저 인정하고, 부드러운 응원 한 마디를 덧붙입니다
            - 진단, 조언, 질문 형식은 사용하지 않습니다
            - 자연스럽고 친근한 말투를 사용합니다
            """;

    private final GeminiLlmClient geminiLlmClient;
    private final JdbcTemplate jdbcTemplate;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generateAndSave(UUID checkinId, String emotionType, int conditionScore, String timeOfDay) {
        try {
            String userMessage = buildPrompt(emotionType, conditionScore, timeOfDay);
            String response = geminiLlmClient.complete(
                    LlmRequest.of("gemini-2.0-flash", SYSTEM_PROMPT, userMessage));

            if (response == null || response.isBlank()) {
                log.debug("[CheckinAiResponseGenerator] no response for checkinId={}", checkinId);
                return;
            }

            jdbcTemplate.update(
                    "UPDATE checkins SET ai_response = ? WHERE id = ?",
                    response.trim(), checkinId);

            log.debug("[CheckinAiResponseGenerator] saved ai_response checkinId={}", checkinId);
        } catch (Exception e) {
            log.warn("[CheckinAiResponseGenerator] failed for checkinId={}: {}", checkinId, e.getMessage());
        }
    }

    private String buildPrompt(String emotionType, int conditionScore, String timeOfDay) {
        String timeLabel = switch (timeOfDay) {
            case "morning" -> "아침";
            case "afternoon" -> "점심";
            default -> "저녁";
        };
        return String.format(
                "시간대: %s\n감정: %s\n감정 강도: %d/5",
                timeLabel, emotionType, conditionScore);
    }
}
