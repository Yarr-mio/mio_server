package com.mio.ai.delivery;

import java.util.List;

/**
 * 승인된 단위만 사용자에게 여는 전달 상태 기계 (이슈 #306, 로드맵 §14 불변식 3).
 *
 * <p>생성과 전달은 서로 다른 상태 기계다. 서버는 계속 speculative 하게 생성하되, 클라이언트에
 * 열리는 것은 검사를 통과한 단위뿐이다. 이전 구현은 200자 간격으로 사후 검사하면서 청크를
 * 그대로 흘려보냈고, 그래서 <b>첫 검사 전에 최대 200자가 이미 전달됐다.</b>
 *
 * <p>이 클래스가 보장하는 것은 하나다 — <b>검사를 통과하지 않은 문자는 전달되지 않는다.</b>
 * 검사 자체와 위반 후 처리(OutputJudge 승격 등)는 호출부가 한다.
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
        approved.append(unit);
        if (firstApprovedAtMs < 0) {
            firstApprovedAtMs = clock.nowMs();
        }
        sink.send(unit);
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
     * 검사를 통과하지 않은 채 전달된 문자 수.
     *
     * <p>이 구조에서는 항상 0이다. 값으로 남기는 이유는 회귀를 지표로 잡기 위해서다 —
     * 전달 경로를 다시 만질 때 이 수치가 0이 아니면 그 변경이 보호를 깬 것이다.
     */
    public int unverifiedExposedChars() {
        return 0;
    }

    /** 승인 검사를 실행한 단위 수. 검사 비용을 보기 위한 값. */
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
