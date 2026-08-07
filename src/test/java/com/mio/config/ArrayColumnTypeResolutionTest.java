package com.mio.config;

import jakarta.persistence.Entity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.Selectable;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.JdbcTypeNameMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이슈 #374 회귀 방지 — 배열 컬럼(`columnDefinition`이 `[]`로 끝나는 컬럼)이
 * 엔티티 처리 순서와 무관하게 항상 {@code Types#ARRAY}로 해석되는지 검증한다.
 *
 * <p>배경: Hibernate는 한 Java 타입의 {@code BasicType}을 전역 단일 슬롯에 등록한다.
 * 같은 Java 타입(예: {@code List<String>})을 한쪽에서 {@code SqlTypes.ARRAY}로,
 * 다른 쪽에서 {@code SqlTypes.JSON}으로 매핑하면 두 해석이 경합해 먼저 처리된 쪽이
 * 이긴다. 엔티티 처리 순서는 클래스패스 스캔 순서(= jar 엔트리 순서, 빌드마다 달라짐)에
 * 달려 있어, 배열 컬럼이 jsonb로 해석되면 기동 시 스키마 검증이 실패한다
 * (2026-08-06 / 08-07 프로덕션 장애).
 *
 * <p>이 테스트는 DB 연결 없이 Hibernate {@code Metadata}만 빌드하므로 CI에서 가볍게 돈다.
 * 실제 장애 순서를 재현하기 위해 <b>JSON으로 매핑된 컬렉션 필드를 가진 엔티티를 맨 앞에</b>
 * 두는 적대적 순서를 포함한다.
 */
class ArrayColumnTypeResolutionTest {

    private static final String ENTITY_BASE_PACKAGE = "com.mio";

    @Test
    @DisplayName("배열 컬럼은 엔티티 처리 순서와 무관하게 Types#ARRAY로 해석된다 (#374)")
    void arrayColumnsResolveToArray_regardlessOfEntityOrder() {
        List<String> scanned = scanEntityClassNames();
        assertThat(scanned)
                .as("com.mio 하위 @Entity 스캔 결과가 비어 있으면 이 테스트는 아무것도 검증하지 못한다")
                .isNotEmpty();

        for (List<String> ordering : hostileOrderings(scanned)) {
            List<String> mismatches = arrayColumnMismatches(ordering);
            assertThat(mismatches)
                    .as("배열 컬럼이 ARRAY 이외로 해석됐다 (선두 엔티티=%s)", ordering.get(0))
                    .isEmpty();
        }
    }

    /**
     * 검증할 엔티티 순서 목록. 스캔 순서, 역순, 그리고 JSON 컬렉션 필드를 가진 엔티티를
     * 각각 맨 앞에 세운 순서를 포함한다(실제 장애를 일으켰던 조건).
     */
    private static List<List<String>> hostileOrderings(List<String> scanned) {
        List<List<String>> orderings = new ArrayList<>();
        orderings.add(new ArrayList<>(scanned));

        List<String> reversed = new ArrayList<>(scanned);
        java.util.Collections.reverse(reversed);
        orderings.add(reversed);

        for (String jsonCollectionEntity : entitiesWithJsonCollectionField(scanned)) {
            List<String> first = new ArrayList<>(scanned);
            first.remove(jsonCollectionEntity);
            first.add(0, jsonCollectionEntity);
            orderings.add(first);
        }
        return orderings;
    }

    /**
     * `columnDefinition`이 `[]`로 끝나는 컬럼 중 {@code Types#ARRAY}로 해석되지 않은 것들.
     * 컬럼을 하드코딩하지 않으므로 앞으로 추가되는 배열 컬럼도 자동으로 검증 대상이 된다.
     */
    private static List<String> arrayColumnMismatches(List<String> entityOrder) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                // DB 연결 없이 Metadata만 빌드한다
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.connection.provider_class",
                        "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl")
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            entityOrder.forEach(sources::addAnnotatedClassName);
            Metadata metadata = sources.buildMetadata();

            List<String> mismatches = new ArrayList<>();
            for (PersistentClass persistentClass : metadata.getEntityBindings()) {
                for (Property property : persistentClass.getPropertyClosure()) {
                    for (Selectable selectable : property.getValue().getSelectables()) {
                        if (!(selectable instanceof Column column)) {
                            continue;
                        }
                        if (!column.getSqlType(metadata).endsWith("[]")) {
                            continue;
                        }
                        int typeCode = column.getSqlTypeCode(metadata);
                        if (typeCode != SqlTypes.ARRAY) {
                            mismatches.add("%s.%s: %s (Types#%s)".formatted(
                                    persistentClass.getTable().getName(),
                                    column.getName(),
                                    column.getSqlType(metadata),
                                    JdbcTypeNameMapper.getTypeName(typeCode)));
                        }
                    }
                }
            }
            return mismatches;
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static List<String> scanEntityClassNames() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Set<String> names = new LinkedHashSet<>();
        scanner.findCandidateComponents(ENTITY_BASE_PACKAGE)
                .forEach(definition -> names.add(definition.getBeanClassName()));
        return new ArrayList<>(names).stream().sorted().toList();
    }

    /** {@code @JdbcTypeCode(SqlTypes.JSON)}이 붙은 컬렉션 필드를 가진 엔티티. */
    private static List<String> entitiesWithJsonCollectionField(List<String> entityClassNames) {
        List<String> result = new ArrayList<>();
        for (String className : entityClassNames) {
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                JdbcTypeCode annotation = field.getAnnotation(JdbcTypeCode.class);
                if (annotation != null
                        && annotation.value() == SqlTypes.JSON
                        && Collection.class.isAssignableFrom(field.getType())) {
                    result.add(className);
                    break;
                }
            }
        }
        return result;
    }
}
