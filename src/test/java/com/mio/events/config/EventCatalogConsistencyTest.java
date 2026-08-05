package com.mio.events.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이슈 #330 — event-catalog.yml(사람이 읽는 참조용 카탈로그)과 event-whitelist.yml(런타임
 * 검증 설정)은 물리적으로 다른 파일이라, 이벤트나 property를 추가/삭제할 때 한쪽만 고치고
 * 다른 쪽을 깜빡하면 조용히 어긋난다. 이벤트 이름과 property 키 집합이 두 파일에서 완전히
 * 일치하는지 CI에서 강제한다.
 */
class EventCatalogConsistencyTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadEvents(String resourcePath) throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            return (Map<String, Object>) root.get("events");
        }
    }

    /** whitelist는 {key: {spec}} 맵, catalog는 {properties: [key, ...]} — 형식은 다르지만 키 집합만 비교한다. */
    @SuppressWarnings("unchecked")
    private Set<String> whitelistPropertyKeys(Object rawProperties) {
        if (!(rawProperties instanceof Map<?, ?> propertiesMap)) {
            return Set.of();
        }
        return new TreeSet<>((Set<String>) propertiesMap.keySet());
    }

    @SuppressWarnings("unchecked")
    private Set<String> catalogPropertyKeys(Map<String, Object> catalogEvent) {
        List<String> properties = (List<String>) catalogEvent.get("properties");
        return properties == null ? Set.of() : new TreeSet<>(properties);
    }

    @Test
    void catalogAndWhitelist_haveSameEventNames() throws Exception {
        Map<String, Object> catalog = loadEvents("event-catalog.yml");
        Map<String, Object> whitelist = loadEvents("event-whitelist.yml");

        assertThat(catalog.keySet())
                .as("event-catalog.yml과 event-whitelist.yml의 이벤트 이름 집합이 달라졌다 — 둘 다 함께 고쳐야 한다")
                .containsExactlyInAnyOrderElementsOf(whitelist.keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void catalogAndWhitelist_havePropertyKeysPerEvent() throws Exception {
        Map<String, Object> catalog = loadEvents("event-catalog.yml");
        Map<String, Object> whitelist = loadEvents("event-whitelist.yml");

        for (Map.Entry<String, Object> entry : whitelist.entrySet()) {
            String eventName = entry.getKey();
            Object catalogEntry = catalog.get(eventName);

            assertThat(catalogEntry)
                    .as("event-catalog.yml에 '%s' 이벤트가 없다 — event-whitelist.yml과 동기화 필요", eventName)
                    .isNotNull();

            Set<String> whitelistKeys = whitelistPropertyKeys(entry.getValue());
            Set<String> catalogKeys = catalogPropertyKeys((Map<String, Object>) catalogEntry);

            assertThat(catalogKeys)
                    .as("이벤트 '%s'의 property 키가 event-catalog.yml(%s)과 event-whitelist.yml(%s)에서 다르다",
                            eventName, catalogKeys, whitelistKeys)
                    .containsExactlyInAnyOrderElementsOf(whitelistKeys);
        }
    }
}
