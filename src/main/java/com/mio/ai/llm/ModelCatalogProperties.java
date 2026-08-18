package com.mio.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 역할→모델 설정 (#479).
 *
 * <pre>
 * mio:
 *   ai:
 *     models:
 *       allowed: [gpt-4o, gpt-4o-mini]   # 이 밖의 모델로는 기동 자체가 안 된다
 *       roles:
 *         generation: gpt-4o             # 생략하면 ModelRole 의 기본값
 * </pre>
 *
 * <p>{@code allowed} 등재는 PR 로만 한다 — 모델 변경이 안전 게이트(Crisis Eval)를
 * 우회하지 않게 하는 강제 지점이 이 목록이다. 검증 규칙은 {@link ModelCatalog} 에 있다.
 */
@ConfigurationProperties(prefix = "mio.ai.models")
public class ModelCatalogProperties {

    private Map<String, String> roles = new LinkedHashMap<>();
    private List<String> allowed = new ArrayList<>();

    public Map<String, String> getRoles() {
        return Map.copyOf(roles);
    }

    public void setRoles(Map<String, String> roles) {
        this.roles = roles != null ? new LinkedHashMap<>(roles) : new LinkedHashMap<>();
    }

    public List<String> getAllowed() {
        return List.copyOf(allowed);
    }

    public void setAllowed(List<String> allowed) {
        this.allowed = allowed != null ? new ArrayList<>(allowed) : new ArrayList<>();
    }
}
