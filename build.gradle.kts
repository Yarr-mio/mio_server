plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.mio"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Metrics — Micrometer 카운터를 Prometheus 포맷으로 스크레이프 가능하게 만든다.
    // 이 레지스트리가 없으면 코드가 기록하는 지표를 프로세스 밖에서 읽을 방법이 없다.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-database-postgresql")

    // pgvector
    implementation("com.pgvector:pgvector:0.1.6")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // OpenAPI / Swagger UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // dotenv
    implementation("me.paulschwarz:spring-dotenv:4.0.0")
    // Firebase Admin SDK — Android FCM push
    implementation("com.google.firebase:firebase-admin:9.4.1")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("it.ozimov:embedded-redis:0.7.3") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 실 LLM API를 호출하는 테스트는 과금이 발생하므로 기본 실행에서 제외한다.
// 포함하려면: ./gradlew test -PllmTests
val runLlmTests = project.hasProperty("llmTests")

tasks.withType<Test> {
    useJUnitPlatform {
        if (!runLlmTests) {
            excludeTags("llm-integration")
        }
    }

    // 스케일 테스트 표본 수 조정: ./gradlew test -PllmTests -PllmScaleSample=1000
    project.findProperty("llmScaleSample")?.let {
        systemProperty("llm.scale.sample", it.toString())
    }

    // 평가 실행 아카이브 위치. 기본은 build/eval-runs (커밋 대상 아님).
    // 기준선으로 저장소에 남길 실행만: ./gradlew test -PevalArchiveDir=docs/eval/runs
    project.findProperty("evalArchiveDir")?.let {
        systemProperty("mio.eval.archiveDir", it.toString())
    }

    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val idx = trimmed.indexOf('=')
                if (idx > 0) {
                    val key = trimmed.substring(0, idx).trim()
                    val value = trimmed.substring(idx + 1).trim()
                    environment(key, value)
                }
            }
        }
    }
}
