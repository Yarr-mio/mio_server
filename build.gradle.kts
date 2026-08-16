plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.mio"
version = "1.0.0"

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
    // AWS CloudWatch — 인프라 비용 배치 캐싱(이슈 #437). AWS/Billing EstimatedCharges 지표를 읽는다 —
    // Cost Explorer(ce:GetCostAndUsage, 건당 $0.01)보다 이쪽이 무료라 이걸로 전환함(리뷰 반영).
    // 크리덴셜은 EC2 인스턴스 역할 기본 체인.
    implementation("software.amazon.awssdk:cloudwatch:2.28.11")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.micrometer:micrometer-registry-prometheus")
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

    // ── A~E 셀 벤치마크 (이슈 #454, 로드맵 §11.3) ────────────────────
    //
    // 상위 모델 후보 ID 와 단가는 코드에 없다. 실행 직전에 여기로 핀한다 — 로드맵이
    // "정확한 후보 ID 와 당시 단가는 각 벤치마크 실행의 registry 에 핀한다" 고 정했다.
    //
    //   ./gradlew test -PllmTests -Pcells=A,D -PsampleSize=20 \
    //     -PcellModels="generation=<후보 ID>,escalation=<후보 ID>" \
    //     -PcellPrices="<후보 ID>=5.0/2.5/20.0" -PpricingAsOf=2026-08-16
    //
    // 견적은 태그 없이 돌아간다: ./gradlew test --tests "com.mio.ai.qa.CellCostEstimateTest"
    // 상위 모델 후보를 여럿 지정하면 상위 모델을 쓰는 셀이 후보 수만큼 변형으로 펼쳐진다.
    // 전부 같은 실행·같은 기준선 A 아래에서 돌기 때문에 후보끼리 비교할 수 있다 —
    // 실행을 나눠 돌린 결과는 RunIdentity 가 비교를 거부한다.
    //
    //   ./gradlew test -PllmTests -Pcells=A,B -PsampleSize=60 \
    //     -PfrontierCandidates="<후보1>,<후보2>,<후보3>" \
    //     -PcellPrices="<후보1>=2.0/0.2/12.0" -PpricingAsOf=2026-08-16
    project.findProperty("frontierCandidates")?.let {
        systemProperty("mio.eval.frontierCandidates", it.toString())
    }
    // 모델 선정 깔때기의 단계. 표본 수와 후보 정책이 단계에 묶여 따라온다.
    //   -Pstage=screen     1단계 — 명부의 생성 후보 전부, 표본 50건
    //   -Pstage=semifinal  2단계 — 1단계 생존자, 표본 150건
    //   -Pstage=full       3단계 — 역할별 결선, 전량 323건 (여기서만 Go/No-Go 가 나온다)
    project.findProperty("stage")?.let { systemProperty("mio.eval.stage", it.toString()) }
    // Batch API 품질 전용 모드 (1단계 한정). 지연·전달 지표는 측정되지 않는다.
    project.findProperty("batchQuality")?.let {
        systemProperty("mio.eval.batchQuality", it.toString())
    }
    // 1단계 생존자용 동기 지연 프로브 표본 수. batch 로 못 잰 p95 를 여기서 잰다.
    project.findProperty("latencyProbe")?.let {
        systemProperty("mio.eval.latencyProbe", it.toString())
    }
    // 추론 모델(o 계열·pro 계열)의 completion 토큰 배수 가정. 견적에만 쓴다.
    project.findProperty("reasoningMultiplier")?.let {
        systemProperty("mio.eval.reasoningMultiplier", it.toString())
    }
    project.findProperty("cells")?.let { systemProperty("mio.eval.cells", it.toString()) }
    project.findProperty("sampleSize")?.let { systemProperty("mio.eval.sampleSize", it.toString()) }
    project.findProperty("pricingAsOf")?.let { systemProperty("mio.eval.pricingAsOf", it.toString()) }
    project.findProperty("evalSeed")?.let { systemProperty("mio.eval.seed", it.toString()) }
    project.findProperty("cellRegistry")?.let { systemProperty("mio.eval.registry", it.toString()) }

    // "<역할>=<모델 ID>" 목록 → mio.eval.model.<역할>
    project.findProperty("cellModels")?.toString()
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.forEach { entry ->
            val idx = entry.indexOf('=')
            require(idx > 0) { "cellModels 항목 형식이 잘못됐다: '$entry' — <역할>=<모델 ID> 여야 한다" }
            systemProperty(
                "mio.eval.model." + entry.substring(0, idx).trim().lowercase(),
                entry.substring(idx + 1).trim()
            )
        }

    // "<모델 ID>=<input>/<cachedInput>/<output>" 목록 → mio.eval.price.<모델 ID>
    project.findProperty("cellPrices")?.toString()
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.forEach { entry ->
            val idx = entry.indexOf('=')
            require(idx > 0) {
                "cellPrices 항목 형식이 잘못됐다: '$entry' — <모델 ID>=<input>/<cachedInput>/<output> 여야 한다"
            }
            systemProperty(
                "mio.eval.price." + entry.substring(0, idx).trim(),
                entry.substring(idx + 1).trim()
            )
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
