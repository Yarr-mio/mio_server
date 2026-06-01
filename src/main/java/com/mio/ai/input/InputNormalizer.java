package com.mio.ai.input;

import org.springframework.stereotype.Component;

@Component
public class InputNormalizer {

    public String normalize(String text) {
        if (text == null) return "";
        return text.strip()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }
}
