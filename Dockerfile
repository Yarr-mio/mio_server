# builder 를 빌드 플랫폼(러너 아키텍처)에 고정한다.
# JVM 바이트코드는 아키텍처 중립이라 Gradle 빌드는 네이티브로 돌리고 런타임 스테이지만
# 타깃 아키텍처로 만들면 된다. 이 고정이 없으면 arm64 빌드 시 Gradle 전체가 QEMU
# 에뮬레이션으로 실행되어 빌드가 10배 가까이 느려진다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
