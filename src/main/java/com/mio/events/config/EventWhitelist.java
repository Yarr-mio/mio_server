package com.mio.events.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 강민석 "mio 이벤트 로그 명세 v3.4" §4 카탈로그를 설정 파일로 관리한다 (하드코딩 금지 —
 * 명세 §5 요구사항). 온보딩 개편 같은 카탈로그 변경은 event-whitelist.yml 한 곳만 고치면 된다.
 */
@Component
public class EventWhitelist {

    private static final String RESOURCE_PATH = "event-whitelist.yml";

    private Map<String, List<String>> allowedPropertiesByEvent;

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            allowedPropertiesByEvent = (Map<String, List<String>>) root.get("events");
        } catch (IOException e) {
            throw new IllegalStateException("event-whitelist.yml 로드 실패", e);
        }
    }

    public boolean isKnownEvent(String eventName) {
        return allowedPropertiesByEvent.containsKey(eventName);
    }

    public Set<String> allowedProperties(String eventName) {
        List<String> keys = allowedPropertiesByEvent.get(eventName);
        return keys == null ? Set.of() : Set.copyOf(keys);
    }
}
