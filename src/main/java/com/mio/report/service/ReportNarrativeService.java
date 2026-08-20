package com.mio.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mio.ai.llm.ModelCatalog;
import com.mio.ai.llm.ModelRole;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.report.dto.ReportCommonDto.DistortionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 리포트 내러티브 생성기.
 *
 * <p><b>현재 프로덕션 참조가 없다</b> (이슈 #419). 주간·월간 조회 경로가 모두 배치 저장분을
 * 읽도록 바뀌면서 호출부가 사라졌고, 주간 내러티브는 {@code WeeklyReflectionJob} 이 자체
 * 프롬프트로 생성한다.
 *
 * <p>삭제하지 않고 남겨 둔 이유는 <b>월간 내러티브 artifact 방식이 아직 확정되지 않았기
 * 때문</b>이다 (API 명세 {@code 08_Report_리포트.md} — "별건으로 확정"). 월간 배치를 만들 때
 * 이 클래스가 그대로 쓰인다. 그 결정이 "월간 내러티브를 두지 않는다" 로 나면 이 클래스와
 * {@code ModelRole.REPORT_NARRATIVE} 를 함께 제거한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReportNarrativeService {

    // 리포트 서술 출력 상한. 프롬프트가 3문장·100자를 요구한다.
    private static final int MAX_COMPLETION_TOKENS = 400;

    private static final String SYSTEM_PROMPT = """
            당신은 CBT 기반 심리 코칭 전문가입니다.
            사용자의 리포트 데이터를 바탕으로 따뜻하고 공감적인 코칭 내러티브를 작성하세요.

            다음 JSON 형식으로만 응답하세요:
            {
              "narrative": "이번 기간에 대한 전체적인 공감·요약 (2~3문장, 100자 이내)",
              "coaching_direction": "인지 왜곡 패턴이나 감정 점수 기반 구체적 행동 제안 (2~3문장, 100자 이내)"
            }
            """;

    private final LlmClient llmClient;
    private final ModelCatalog modelCatalog;
    private final ObjectMapper objectMapper;

    public record NarrativeResult(String narrative, String coachingDirection) {
        public static NarrativeResult empty() {
            return new NarrativeResult(null, null);
        }
    }

    public NarrativeResult generate(String periodLabel, int checkinCount, Double avgEmotionScore,
                                    List<DistortionDto> distortionTop3, UUID userId) {
        String userMessage = buildUserMessage(periodLabel, checkinCount, avgEmotionScore, distortionTop3);
        try {
            String response = llmClient.completeJson(LlmRequest.of(modelCatalog.modelFor(ModelRole.REPORT_NARRATIVE), SYSTEM_PROMPT, userMessage)
                    .withMaxCompletionTokens(MAX_COMPLETION_TOKENS)
                    .withAttribution("REPORT_NARRATIVE", userId, null));
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("ReportNarrative generation failed: period={} error={}", periodLabel, e.getClass().getSimpleName());
            return NarrativeResult.empty();
        }
    }

    private String buildUserMessage(String periodLabel, int checkinCount, Double avgEmotionScore,
                                    List<DistortionDto> distortionTop3) {
        StringBuilder sb = new StringBuilder();
        sb.append("기간: ").append(periodLabel).append("\n");
        sb.append("체크인 횟수: ").append(checkinCount).append("회\n");
        if (avgEmotionScore != null) {
            sb.append("평균 감정 점수: ").append(avgEmotionScore).append(" / 100\n");
        }
        if (distortionTop3 != null && !distortionTop3.isEmpty()) {
            sb.append("주요 인지 왜곡: ");
            for (DistortionDto d : distortionTop3) {
                sb.append(d.label()).append("(").append(d.count()).append("회) ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private NarrativeResult parseResponse(String json) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json\\n?", "").replaceAll("```", "").trim();
            }
            var node = objectMapper.readTree(cleaned);
            String narrative = node.has("narrative") && !node.get("narrative").isNull()
                    ? node.get("narrative").asText() : null;
            String coachingDirection = node.has("coaching_direction") && !node.get("coaching_direction").isNull()
                    ? node.get("coaching_direction").asText() : null;
            return new NarrativeResult(narrative, coachingDirection);
        } catch (Exception e) {
            log.warn("ReportNarrative response parsing failed: {}", json, e);
            return NarrativeResult.empty();
        }
    }
}
