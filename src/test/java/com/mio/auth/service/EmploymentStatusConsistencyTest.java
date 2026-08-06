package com.mio.auth.service;

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
 * 이슈 #347 — event-whitelist.yml의 employment_status enum과
 * AuthService.VALID_EMPLOYMENT_STATUSES는 물리적으로 다른 곳에 있어 값 집합이 조용히
 * 갈라질 수 있다(#330 CI는 property 키만 비교하고 enum 값은 비교하지 않는다). 두 값 집합이
 * 항상 일치하는지 여기서 강제한다.
 */
class EmploymentStatusConsistencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void whitelistAndAuthService_haveSameEmploymentStatusValues() throws Exception {
        Yaml yaml = new Yaml();
        Map<String, Object> events;
        try (InputStream in = new ClassPathResource("event-whitelist.yml").getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            events = (Map<String, Object>) root.get("events");
        }

        Map<String, Object> profileSubmitted = (Map<String, Object>) events.get("profile_submitted");
        Map<String, Object> employmentStatusSpec = (Map<String, Object>) profileSubmitted.get("employment_status");
        Set<String> whitelistValues = new TreeSet<>((List<String>) employmentStatusSpec.get("enum"));

        assertThat(new TreeSet<>(AuthService.VALID_EMPLOYMENT_STATUSES))
                .as("event-whitelist.yml의 employment_status enum(%s)과 "
                        + "AuthService.VALID_EMPLOYMENT_STATUSES(%s)가 다르다 — 한쪽만 고치면 "
                        + "이벤트 property 드롭 또는 가입 API 400 회귀가 생긴다",
                        whitelistValues, AuthService.VALID_EMPLOYMENT_STATUSES)
                .isEqualTo(whitelistValues);
    }
}
