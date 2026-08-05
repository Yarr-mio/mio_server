package com.mio.events.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 강민석 "mio 이벤트 로그 명세" §4 카탈로그를 설정 파일로 관리한다 (하드코딩 금지 —
 * 명세 §5 요구사항). 온보딩 개편 같은 카탈로그 변경은 event-whitelist.yml 한 곳만 고치면 된다.
 *
 * <p>#320 — 키 목록뿐 아니라 값 도메인(enum/type)까지 검증한다. 키만 검증하면 enum 자리에
 * 자유 문자열이 실려도 통과했다(예: employment_status).
 */
@Component
public class EventWhitelist {

    private static final String RESOURCE_PATH = "event-whitelist.yml";

    public enum PropertyType { ENUM, INT, BOOL, ID, STRING }

    public record PropertySpec(PropertyType type, Set<String> enumValues) {

        /** null은 항상 통과시킨다 — nullable property(예: ai_emotion_score)가 정상 값이다. */
        boolean accepts(Object value) {
            if (value == null) {
                return true;
            }
            return switch (type) {
                case ENUM -> value instanceof String s && enumValues.contains(s);
                case INT -> value instanceof Integer || value instanceof Long;
                case BOOL -> value instanceof Boolean;
                case ID -> value instanceof String s && !s.isBlank();
                case STRING -> value instanceof String;
            };
        }
    }

    private Map<String, Map<String, PropertySpec>> catalog;

    @PostConstruct
    @SuppressWarnings("unchecked")
    void load() {
        Yaml yaml = new Yaml();
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            Map<String, Object> events = (Map<String, Object>) root.get("events");
            catalog = new LinkedHashMap<>();
            events.forEach((eventName, rawProperties) -> catalog.put(eventName, parseProperties(rawProperties)));
        } catch (IOException e) {
            throw new IllegalStateException("event-whitelist.yml 로드 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, PropertySpec> parseProperties(Object rawProperties) {
        if (!(rawProperties instanceof Map<?, ?> propertiesMap)) {
            return Map.of();
        }
        Map<String, PropertySpec> specs = new LinkedHashMap<>();
        propertiesMap.forEach((key, spec) -> specs.put((String) key, parseSpec((Map<String, Object>) spec)));
        return specs;
    }

    @SuppressWarnings("unchecked")
    private PropertySpec parseSpec(Map<String, Object> spec) {
        if (spec.containsKey("enum")) {
            List<String> values = (List<String>) spec.get("enum");
            return new PropertySpec(PropertyType.ENUM, Set.copyOf(values));
        }
        PropertyType type = switch (String.valueOf(spec.get("type"))) {
            case "int" -> PropertyType.INT;
            case "bool" -> PropertyType.BOOL;
            case "id" -> PropertyType.ID;
            default -> PropertyType.STRING;
        };
        return new PropertySpec(type, Set.of());
    }

    public boolean isKnownEvent(String eventName) {
        return catalog.containsKey(eventName);
    }

    public Set<String> allowedProperties(String eventName) {
        Map<String, PropertySpec> specs = catalog.get(eventName);
        return specs == null ? Set.of() : Set.copyOf(specs.keySet());
    }

    /** 키가 카탈로그에 없거나, 있어도 값이 도메인 밖이면 false. */
    public boolean isValidPropertyValue(String eventName, String key, Object value) {
        Map<String, PropertySpec> specs = catalog.get(eventName);
        if (specs == null) {
            return false;
        }
        PropertySpec spec = specs.get(key);
        return spec != null && spec.accepts(value);
    }
}
