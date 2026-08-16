package com.mio.ai.delivery;

import com.mio.ai.policy.PolicyDecision;
import com.mio.session.dto.SseEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 검토된 첫 문장의 전달 절차 (P0-4, 로드맵 §5.6).
 *
 * <p>{@link SafePrefixCatalog} 가 <b>무엇을 보낼지</b>를 정하고, 이 클래스가 <b>언제·어떻게
 * 보내고 지우는지</b>를 담당한다. {@code ConversationOrchestrator} 에 두면 이미 상한을 넘긴
 * 파일이 더 커지고, 전달 규칙이 턴 흐름 코드 사이에 흩어진다 — 이슈 #306 이
 * {@link HoldbackDelivery} 를 분리한 것과 같은 이유다.
 *
 * <p>SSE 전송 자체는 하지 않는다. 호출부가 {@link EventSender} 로 넘긴다 — 여기서 emitter 를
 * 직접 다루면 전달 규칙과 전송 구현이 다시 묶인다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SafePrefixDelivery {

    private final SafePrefixCatalog catalog;

    /** 이벤트 하나를 실제로 내보낸다. 실패는 예외로 알린다. */
    @FunctionalInterface
    public interface EventSender {
        void send(SseEventDto event) throws Exception;
    }

    /**
     * 이 턴에 검토된 첫 문장을 보낸다.
     *
     * <p>선택과 전송 실패를 모두 삼킨다 — prefix 는 지연 개선 수단이지 응답의 필수 부분이
     * 아니므로, 여기서 예외를 올리면 원래 성공했을 턴이 prefix 때문에 실패한다.
     *
     * @return 실제로 전달된 문구. 대상이 아니거나 전송에 실패했으면 {@code null}
     */
    public String deliverFor(PolicyDecision decision, EventSender sender, String outboundMsgId,
                             AtomicLong firstRenderedTokenMs, long pipelineStartedAtMs) {
        String prefix = catalog.select(decision).orElse(null);
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        try {
            sender.send(new SseEventDto.DeltaEvent(prefix, outboundMsgId));
            firstRenderedTokenMs.compareAndSet(
                    -1, Math.max(0, System.currentTimeMillis() - pipelineStartedAtMs));
            return prefix;
        } catch (Exception e) {
            log.warn("Safe prefix not delivered — continuing without it: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 위기 안내를 보내기 전에 이미 렌더된 서버 문구를 지운다 (안전 리뷰 HIGH).
     *
     * <p>{@code CAUTIOUS_SPECULATIVE} 의 OutputJudge 는 사전 위험 라벨이 아니라 <b>모델이 실제로
     * 생성한 텍스트</b>를 보고 판정한다. 그래서 prefix 를 받는 MEDIUM·LOW 턴에서도
     * {@code CRISIS_FLOW} 가 나올 수 있고, 그때 나가는 것은 {@code delta.replace} 가 아니라
     * crisis 이벤트다 — 지우지 않으면 사용자는 감정 인정 문장 <b>바로 아래</b>에서 핫라인을
     * 본다. 위험 등급을 좁히는 방식으로는 막을 수 없는 문제다.
     *
     * <p>새 이벤트 타입을 만들지 않는다. 판정 교체 분기가 이미 쓰는 "메시지 전체 교체" 의미를
     * 그대로 쓰되 내용을 비운다 — FE 계약이 바뀌지 않는다.
     *
     * <p>전송 실패는 삼킨다. 이 호출 뒤에 위기 안내 전송이 이어지고 그쪽에 실패 처리가 이미
     * 있다. 여기서 예외를 올리면 위기 안내 자체가 나가지 않는다.
     */
    public void clearRendered(String deliveredPrefix, EventSender sender, String outboundMsgId) {
        if (deliveredPrefix == null || deliveredPrefix.isBlank()) {
            return;
        }
        try {
            sender.send(new SseEventDto.DeltaReplaceEvent("", outboundMsgId));
        } catch (Exception e) {
            log.warn("Safe prefix not cleared before crisis event: {}", e.getMessage());
        }
    }

    /**
     * 서버가 먼저 보낸 문장을 응답 본문 앞에 잇는다.
     *
     * <p>사용자가 읽은 것과 저장·재생되는 것이 같아야 한다. 이어 붙일 때 양쪽 공백을 정리해
     * 문구가 이미 공백으로 끝나거나 본문이 공백으로 시작해도 이중 공백이 생기지 않게 한다.
     */
    public String withPrefix(String deliveredPrefix, String content) {
        if (deliveredPrefix == null || deliveredPrefix.isBlank()) {
            return content;
        }
        if (content == null || content.isBlank()) {
            return deliveredPrefix.strip();
        }
        return deliveredPrefix.strip() + " " + content.strip();
    }

    /**
     * 사용자가 무언가를 보기까지 걸린 시간.
     *
     * <p>safe prefix 가 나간 턴은 그 전송 시각이고, 없으면 첫 승인 콘텐츠 시각과 같다. 둘을
     * 하나로 재면 서버가 먼저 보내는 문구만으로 수치가 좋아져 "지연 개선"과 "지연 은폐"를
     * 구분할 수 없다 (이슈 #306, 14번 리뷰 지적 E).
     */
    public long firstRenderedMs(long renderedMs, long firstSubstantiveMs) {
        return renderedMs >= 0 ? renderedMs : firstSubstantiveMs;
    }
}
