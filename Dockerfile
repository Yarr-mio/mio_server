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
# MaxRAMPercentage 를 명시하지 않으면 기본값 25% 가 적용되고, 컨테이너에 mem_limit 이
# 없으면 그 25% 마저 호스트 전체 RAM 기준으로 산정된다(2026-08-07 장애 기여 요인).
# compose 의 mem_limit(800m) 기준 힙 440MB. 비힙 167MB + 스레드 35MB + 다이렉트/GC 40MB
# 를 더해도 약 685MB 로 상한 안에 115MB 여유가 남는다.
#
# ExitOnOutOfMemoryError: 힙이 고갈된 JVM 이 절뚝이며 버티면 헬스체크는 통과하면서
# 요청만 실패한다. 즉시 종료시켜 restart 정책이 되살리게 한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
