# mio_server

Mio 백엔드 서버 레포지토리입니다.

CBT(인지행동치료) 기반 AI 캐릭터 정서 코칭 플랫폼의 백엔드 서버입니다.  
Java 21 + Spring Boot 3.x 기반의 모듈러 모놀리스 구조로 구성되어 있습니다.

---

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| DB | PostgreSQL 16 + pgvector |
| Cache | Redis 7 |
| Migration | Flyway |
| Auth | Kakao OAuth / Apple Sign In + JWT |
| Build | Gradle |

---

## 사전 요구사항

| 도구 | 버전 |
|---|---|
| Java JDK | 21 ([Temurin 권장](https://adoptium.net/)) |
| Docker Desktop | 최신 |
| Git | - |

---

## 로컬 개발 환경 세팅

### 1. 레포지토리 클론

```bash
git clone <repo-url>
cd mio
```

### 2. Docker 컨테이너 기동 (PostgreSQL + Redis)

```bash
# docker-compose.example.yml 파일 수령 후 복사
# → 파일 수령: 백엔드 개발자에게 문의

cp docker-compose.example.yml docker-compose.yml
docker compose up -d
```

`mio-postgres`와 `mio-redis` 모두 `healthy` 상태여야 서버 기동이 가능합니다.

```bash
docker compose ps
```

### 3. 환경 변수 설정

```bash
# .env.example 파일 수령 후 복사
# → 파일 수령: 백엔드 개발자에게 문의

cp .env.example .env
```

`.env` 파일을 열어 아래 항목을 채웁니다.

| 변수 | 설명 | 수령처 |
|---|---|---|
| `JWT_SECRET` | 256-bit 이상 랜덤 문자열 | `openssl rand -base64 48` 로 직접 생성 |
| `APP_ENCRYPTION_KEY` | 32 bytes base64 키 | `python3 -c "import base64,os; print(base64.b64encode(os.urandom(32)).decode())"` 로 직접 생성 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | Kakao 소셜 로그인 | 백엔드 개발자 문의 |
| `APPLE_TEAM_ID` / `APPLE_KEY_ID` / `APPLE_PRIVATE_KEY` | Apple 소셜 로그인 | 백엔드 개발자 문의 |
| `FCM_CREDENTIALS_JSON` | Android 푸시 알림 | 백엔드 개발자 문의 |

> OAuth 키가 없어도 로그인 API를 제외한 서버 기동은 가능합니다.

### 4. Spring 로컬 설정 파일

```bash
# application-local.yml.example 파일 수령 후 복사
# → 파일 수령: 백엔드 개발자에게 문의

cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

기본 내용 그대로 사용해도 됩니다.

> 한글 입력기가 켜진 상태로 편집하면 YAML 파싱 오류가 발생할 수 있습니다.  
> 파일 첫 줄이 반드시 `spring:` 으로 시작하는지 확인하세요.

### 5. 서버 실행

```bash
./gradlew bootRun
```

Flyway가 시작 시 자동으로 DB 마이그레이션을 수행합니다.

### 6. 동작 확인

```bash
# Health check
curl http://localhost:8080/actuator/health

# Swagger UI
open http://localhost:8080/swagger-ui.html
```

---

## 빌드 및 테스트

```bash
# 빌드 + 테스트 (CI와 동일)
./gradlew build

# 테스트만
./gradlew test

# 특정 테스트 클래스
./gradlew test --tests "com.mio.SomeTest"

# 컴파일만
./gradlew compileJava
```

테스트 리포트: `build/reports/tests/`

---

## 주요 명령어

```bash
# Docker 컨테이너 중지 (데이터 보존)
docker compose stop

# Docker 컨테이너 재시작
docker compose start

# DB 완전 초기화 (로컬 전용)
docker compose down -v && docker compose up -d

# PostgreSQL 직접 접속
docker exec -it mio-postgres psql -U mio -d mio

# Redis 직접 접속
docker exec -it mio-redis redis-cli
```

---

## 문서

상세 설계 문서는 Notion에서 관리됩니다.  
**접근 권한 및 링크는 백엔드 개발자에게 문의하세요.**

문서 목록:
- 개요 및 아키텍처
- 인증 API 설계
- 도메인 API 명세
- AI 파이프라인 설계
- 데이터베이스 스키마
- 인프라 운영 가이드

---

## 브랜치 전략

```
main          ← 배포 브랜치 (직접 push 금지)
develop       ← 개발 통합 브랜치
feat/#<이슈번호>-<기능명>
fix/#<이슈번호>-<버그명>
```

PR은 반드시 관련 이슈 생성 후 올립니다 (`Closes #<번호>` 포함).  
CI 통과 + 최소 1명 Approve 후 머지 가능합니다.
