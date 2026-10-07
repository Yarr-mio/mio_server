package com.mio.ai.qa.correction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mio.ai.qa.correction.CorrectionEvalRunner.ScoredSample;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 실행 결과를 <b>로컬 파일</b>로 남긴다 (이슈 #552). 응답 본문과 판정이 그대로 들어 있으므로
 * 저장소에 넣지 않는다 — 기본 위치 {@code eval-private/runs/} 는 {@code .gitignore} 처리돼 있다.
 *
 * <p>이 파일이 있어야 (1) 채점기 사람 검증(#555)이 응답을 골라 낼 수 있고, (2) 리포트를 다시 만들 때
 * LLM 을 다시 부르지 않아도 된다. 평가용 세트의 실행 파일은 프롬프트를 고치는 쪽에 전달하지 않는다.
 */
final class CorrectionRunArchive {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private CorrectionRunArchive() {}

    static Path write(CorrectionEvalRunner.Result result, Path directory) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(result.identity().runId() + ".json");
            Files.writeString(file, MAPPER.writeValueAsString(toJson(result)), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("실행 결과를 저장하지 못했다: " + directory, e);
        }
    }

    static ObjectNode toJson(CorrectionEvalRunner.Result result) {
        ObjectNode root = MAPPER.createObjectNode();
        CorrectionRunIdentity identity = result.identity();
        ObjectNode identityNode = root.putObject("identity");
        identityNode.put("runId", identity.runId());
        identityNode.put("startedAt", identity.startedAt().toString());
        identityNode.put("setVersion", identity.setVersion());
        identityNode.put("setSha256", identity.setSha256());
        identityNode.put("repeats", identity.repeats());
        identityNode.set("modes", MAPPER.valueToTree(identity.modes()));
        identityNode.set("arms", MAPPER.valueToTree(identity.arms()));
        identityNode.put("generationModel", identity.generationModel());
        identityNode.put("judgeModel", identity.judgeModel());
        identityNode.put("rubricVersion", identity.rubricVersion());
        identityNode.put("candidateBlockFingerprint", CorrectionPromptArm.blockFingerprint());
        ArrayNode samples = root.putArray("samples");
        for (ScoredSample scored : result.samples()) {
            ObjectNode node = samples.addObject();
            node.put("mode", scored.mode());
            node.put("arm", scored.sample().arm().name());
            node.put("caseId", scored.sample().caseId());
            node.put("repeat", scored.sample().repeat());
            node.put("response", scored.sample().response());
            node.put("failed", scored.sample().failed());
            node.put("truncated", scored.sample().truncated());
            if (scored.shape() != null) {
                node.put("questions", scored.shape().questions());
                node.put("sentences", scored.shape().sentences());
            }
            CorrectionVerdict verdict = scored.verdict();
            if (verdict != null) {
                ObjectNode v = node.putObject("verdict");
                v.put("passed", verdict.passed());
                v.put("judgeFailed", verdict.judgeFailed());
                v.set("items", MAPPER.valueToTree(verdict.items()));
                v.set("evidence", MAPPER.valueToTree(verdict.evidence()));
            }
        }
        return root;
    }
}
