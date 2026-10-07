package com.mio.ai.qa.correction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;
import com.mio.ai.memory.working.WorkingMessage;
import com.mio.ai.policy.GenerationMode;
import com.mio.ai.plan.ResponsePlan;
import com.mio.ai.policy.InterventionHints;
import com.mio.ai.prompt.PromptBuilder;
import com.mio.character.domain.CharacterPersona;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 후보 프롬프트와 직접 대화해 보는 도구 (이슈 #551). 실 LLM 을 부른다 — 턴당 몇 원 수준.
 *
 * <p>대화 기록을 로컬 파일에 저장해 gradle 테스트를 턴마다 다시 실행해도 이어서 대화할 수 있게 한다.
 * 프로덕션과 같은 {@code PromptBuilder} + {@link CorrectionPromptArm} 을 쓰므로, 실제 배포됐을 때와
 * 같은 시스템 프롬프트로 응답을 받는다.
 */
final class CorrectionChatSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Turn(String role, String text) {}

    record Reply(String response, boolean truncated, List<Turn> history) {}

    private CorrectionChatSession() {}

    static List<Turn> load(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            List<Turn> turns = new ArrayList<>();
            root.forEach(n -> turns.add(new Turn(n.get("role").asText(), n.get("text").asText())));
            return turns;
        } catch (IOException e) {
            throw new UncheckedIOException("대화 기록을 읽지 못했다: " + file, e);
        }
    }

    static void save(Path file, List<Turn> turns) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ArrayNode root = MAPPER.createArrayNode();
            for (Turn t : turns) {
                ObjectNode n = root.addObject();
                n.put("role", t.role());
                n.put("text", t.text());
            }
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("대화 기록을 저장하지 못했다: " + file, e);
        }
    }

    /** 저장된 기록에 새 사용자 메시지를 이어 붙여 실제로 응답을 생성하고, 기록을 갱신해 돌려준다. */
    static Reply sendAndAppend(LlmClient client, GenerationMode mode, CorrectionPromptArm arm,
                               int maxCompletionTokens, List<Turn> history, String userMessage) {
        String systemPrompt = arm.apply(new PromptBuilder().buildSystemPrompt(mode, InterventionHints.empty(),
                null, CharacterPersona.DEFAULT.characterId(), null, ResponsePlan.unplanned()));
        List<WorkingMessage> priorTurns = new ArrayList<>();
        for (Turn t : history) {
            priorTurns.add("USER".equals(t.role()) ? WorkingMessage.user(t.text()) : WorkingMessage.assistant(t.text()));
        }
        LlmRequest request = LlmRequest.of(CorrectionGenerationRunner.productionModel(), systemPrompt, priorTurns,
                        userMessage)
                .withMaxCompletionTokens(maxCompletionTokens);
        StringBuilder content = new StringBuilder();
        LlmStreamResult result = client.stream(request, content::append);
        List<Turn> updated = new ArrayList<>(history);
        updated.add(new Turn("USER", userMessage));
        updated.add(new Turn("ASSISTANT", content.toString()));
        return new Reply(content.toString(), result.truncated(), updated);
    }
}
