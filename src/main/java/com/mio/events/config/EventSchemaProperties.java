package com.mio.events.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 이슈 #322 — schema_version 허용 집합. 앱 버전이 항상 혼재하므로 고정값 대신 집합으로
 * 관리한다(명세 개정 시 [3, 4]로 잠시 병행).
 */
@Component
@ConfigurationProperties(prefix = "events")
@Getter
@Setter
public class EventSchemaProperties {

    private List<Integer> acceptedSchemaVersions = List.of(3);

    // 이슈 #353 — 검증 기기 격리. 비어 있으면(기본값) 전부 journey-events로 간다.
    private List<String> internalAnonymousIds = List.of();
}
