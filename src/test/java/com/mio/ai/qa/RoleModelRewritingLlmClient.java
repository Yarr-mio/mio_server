package com.mio.ai.qa;

import com.mio.ai.llm.LlmClient;
import com.mio.ai.llm.LlmRequest;
import com.mio.ai.llm.LlmStreamResult;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 셀이 핀한 모델로 요청의 모델을 바꿔 위임하는 {@link LlmClient} 데코레이터.
 *
 * <p>프로덕션의 모델 ID 는 전부 {@code private static final} 상수다
 * ({@code InputJudge.JUDGE_MODEL} 등). A~E 비교를 하려면 셀마다 모델을 갈아야 하는데,
 * 그러자고 프로덕션에 설정 스위치를 새로 뚫으면 <b>벤치마크가 측정하려는 코드 자체를
 * 바꾸는</b> 셈이 된다.
 *
 * <p>대신 요청 경계에서 바꾼다. {@link LlmRequest} 는 record 이고 {@code component} 태그가
 * 이미 역할을 말하고 있으므로, 태그를 보고 모델만 교체한 복사본을 만들어 위임하면 된다.
 * 프로덕션 코드는 한 줄도 바뀌지 않고, 바뀌는 것은 이 실행에서 어떤 모델이 그 역할을
 * 수행했는가뿐이다.
 *
 * <p>매핑에 없는 태그는 <b>건드리지 않는다.</b> 셀이 선언하지 않은 역할까지 조용히 바꾸면
 * 셀 정의와 실제 실행이 달라진다.
 */
final class RoleModelRewritingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final Map<String, String> componentToModel;

    RoleModelRewritingLlmClient(LlmClient delegate, Map<String, String> componentToModel) {
        this.delegate = delegate;
        this.componentToModel = Map.copyOf(componentToModel);
    }

    @Override
    public LlmStreamResult stream(LlmRequest request, Consumer<String> chunkHandler) {
        return delegate.stream(rewrite(request), chunkHandler);
    }

    @Override
    public String completeText(LlmRequest request) {
        return delegate.completeText(rewrite(request));
    }

    @Override
    public String completeJson(LlmRequest request) {
        return delegate.completeJson(rewrite(request));
    }

    LlmRequest rewrite(LlmRequest request) {
        String pinned = componentToModel.get(request.component());
        if (pinned == null || pinned.equals(request.model())) {
            return request;
        }
        return new LlmRequest(pinned, request.messages(), request.maxCompletionTokens(),
                request.component(), request.userId(), request.sessionId());
    }
}
