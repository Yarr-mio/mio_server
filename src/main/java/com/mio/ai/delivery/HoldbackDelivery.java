package com.mio.ai.delivery;

import java.util.List;

/**
 * 승인된 단위만 사용자에게 여는 전달 상태 기계 (이슈 #306, 로드맵 §14 불변식 3).
 *
 * <p>생성과 전달은 서로 다른 상태 기계다. 서버는 계속 speculative 하게 생성하되, 클라이언트에
 * 열리는 것은 검사를 통과한 단위뿐이다. 이전 구현은 200자 간격으로 사후 검사하면서 청크를
 * 그대로 흘려보냈고, 그래서 <b>첫 검사 전에 최대 200자가 이미 전달됐다.</b>
 *
 * <p>이 클래스가 보장하는 것은 하나다 — <b>{@link UnitGate} 를 통과하지 않은 문자는 전달되지
 * 않는다.</b> 검사 자체와 위반 후 처리(OutputJudge 승격 등)는 호출부가 한다.
 *
 * <p>보장의 범위를 정확히 적는다. 이것은 <b>단위별 게이트</b>에 대한 보장이다. 전체 텍스트가
 * 모여야 드러나는 위반(응답 계약 위반, 문장 사이에 걸친 패턴)은 마지막 단위까지 전달된 뒤에야
 * 판정될 수 있고, 그 경우 기존 OutputJudge 승격·교체 경로가 처리한다.
 *
 * <p>스레드 계약: 단일 스레드 호출을 전제한다. 현재 LLM 스트림 콜백이 호출 스레드에서
 * 동기 실행되기 때문이다. 비동기 클라이언트로 바꾸면 내부 상태에 경쟁이 생긴다.
 */
public final class HoldbackDelivery {

    /** 누적 응답을 검사한다. 통과하지 못하면 그 단위부터 전달이 멈춘다. */
    @FunctionalInterface
    public interface UnitGate {
        boolean approves(String accumulatedWithUnit);
    }

    /** 승인된 단위를 실제로 내보낸다. */
    @FunctionalInterface
    public interface UnitSink {
        void send(String unit) throws Exception;
    }

    private final ApprovedUnitBuffer buffer;
    private final UnitGate gate;
    private final UnitSink sink;
    private final Clock clock;

    private final StringBuilder approved = new StringBuilder();
    private boolean blocked;
    private long firstApprovedAtMs = -1;
    private final long startedAtMs;

    /** 시간 측정 지점. 테스트가 실제 시계에 의존하지 않도록 분리한다. */
    @FunctionalInterface
    public interface Clock {
        long nowMs();
    }

    public HoldbackDelivery(ApprovedUnitBuffer buffer, UnitGate gate, UnitSink sink) {
        this(buffer, gate, sink, System::currentTimeMillis);
    }

    public HoldbackDelivery(ApprovedUnitBuffer buffer, UnitGate gate, UnitSink sink, Clock clock) {
        this.buffer = buffer;
        this.gate = gate;
        this.sink = sink;
        this.clock = clock;
        this.startedAtMs = clock.nowMs();
    }

    /** 생성 청크 하나를 받는다. 단위가 완성되면 검사 후 전달한다. */
    public void onChunk(String chunk) throws Exception {
        if (blocked) {
            // 이미 위반이 확인됐다. 뒤에 오는 청크는 생성만 계속되고 전달되지 않는다.
            return;
        }
        for (String unit : buffer.offer(chunk)) {
            if (!release(unit)) {
                return;
            }
        }
    }

    /** 스트림 종료. 남은 텍스트를 마지막 단위로 검사한다. */
    public void finish() throws Exception {
        String remaining = buffer.drain();
        if (!blocked && !remaining.isEmpty()) {
            release(remaining);
        }
    }

    private boolean release(String unit) throws Exception {
        String candidate = approved + unit;
        if (!gate.approves(candidate)) {
            blocked = true;
            return false;
        }
        // 전송이 성공한 뒤에 기록한다. 먼저 기록하면 클라이언트 연결이 끊겨 전송에 실패한
        // 단위까지 "전달됨"으로 남아, 이 클래스가 보장한다고 말하는 것과 값이 어긋난다.
        sink.send(unit);
        approved.append(unit);
        if (firstApprovedAtMs < 0) {
            firstApprovedAtMs = clock.nowMs();
        }
        return true;
    }

    /** 실제로 사용자에게 전달된 텍스트. */
    public String deliveredContent() {
        return approved.toString();
    }

    /** 위반이 확인돼 전달이 중단됐는지. */
    public boolean blocked() {
        return blocked;
    }

    /**
     * 승인되어 전달된 첫 콘텐츠까지 걸린 시간. 아무것도 전달하지 못했으면 {@code -1}.
     *
     * <p>첫 생성 토큰 지연과 구분해야 한다. 둘을 하나로 재면 서버가 먼저 보내는 문구만으로도
     * 수치가 좋아져 "지연 개선"과 "지연 은폐"를 구분할 수 없다.
     */
    public long firstSubstantiveTokenMs() {
        return firstApprovedAtMs < 0 ? -1 : firstApprovedAtMs - startedAtMs;
    }

    /**
     * 생성됐지만 전달되지 않은 문자 수.
     *
     * <p>이전에는 "검증 전 노출 문자 수"를 상수 0으로 반환했다. 그 값은 이 클래스의 제어
     * 흐름상 항상 참이라 <b>회귀를 잡을 수 없었다</b> — 누군가 게이트를 우회하는 전송 경로를
     * 추가해도 상수는 계속 0을 보고한다. 측정할 수 없는 값을 관측 지표로 로그에 남기면
     * 운영에서 잘못된 안심을 준다.
     *
     * <p>대신 실제로 셀 수 있는 값을 남긴다. 위반으로 보류된 분량이 얼마인지는 전달 정책의
     * 비용과 효과를 함께 보여준다. 노출 보장 자체는 {@code DeliveryExposureQaTest} 가
     * 전달된 텍스트를 다시 검사하는 방식으로 검증한다 — 값이 아니라 결과로 확인한다.
     */
    public int heldBackChars(String generatedContent) {
        return generatedContent == null ? 0 : Math.max(0, generatedContent.length() - approved.length());
    }

    /** 승인되어 전달된 문자 수. */
    public int approvedChars() {
        return approved.length();
    }

    /** 편의 메서드 — 청크 목록을 순서대로 처리하고 종료한다. */
    public void consume(List<String> chunks) throws Exception {
        for (String chunk : chunks) {
            onChunk(chunk);
        }
        finish();
    }
}
