package com.mio.ai.judge;

import java.util.List;

public record OutputPreFilterResult(
        boolean passed,
        List<String> failReasons
) {
    public static OutputPreFilterResult pass() {
        return new OutputPreFilterResult(true, List.of());
    }

    public static OutputPreFilterResult fail(List<String> reasons) {
        return new OutputPreFilterResult(false, reasons);
    }
}
