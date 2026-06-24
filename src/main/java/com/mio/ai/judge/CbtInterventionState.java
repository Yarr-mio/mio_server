package com.mio.ai.judge;

import java.util.Locale;

public enum CbtInterventionState {
    NONE("none"),
    SOCRATIC_ASKED("socratic_asked"),
    FOLLOWUP_NEEDED("followup_needed"),
    COMPLETED("completed");

    private final String wireValue;

    CbtInterventionState(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static CbtInterventionState fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (CbtInterventionState state : values()) {
            if (state.wireValue.equals(normalized)) {
                return state;
            }
        }
        return NONE;
    }
}
