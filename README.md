# reberu

팀 단위 업무·출석 관리 서비스입니다. 업무와 출석 활동에 레벨업 같은 게임 요소를 더했습니다.

- Java 17, Spring Boot 4.1.2, Gradle Kotlin DSL
- PostgreSQL 17, Spring Data JPA, Flyway
- Spring Security (Google OAuth2, 서버 세션)

## 준비물

- JDK 17 (`JAVA_HOME`이 JDK 17 이상을 가리켜야 합니다)
- Docker Desktop

## 로컬 실행

```bash
# 1. 환경 변수 파일 만들기 (.env는 Git에 올라가지 않습니다)
cp .env.example .env
# .env를 열어 DB_PASSWORD, CURSOR_SIGNING_KEY 값을 채웁니다.
# CURSOR_SIGNING_KEY는 openssl rand -base64 32 로 만듭니다.

# 2. PostgreSQL 실행
docker compose up -d

# 3. 애플리케이션 실행
./gradlew bootRun
```

아직 로그인 화면을 만들지 않았기 때문에, 브라우저로 접속하면 Spring Security 기본 로그인 화면이 나오거나 401 응답이 옵니다. 정상 동작입니다.

## 테스트

```bash
./gradlew test
```

테스트는 Testcontainers로 PostgreSQL 컨테이너를 직접 띄우므로 Docker가 실행 중이어야 합니다. `.env`는 필요하지 않습니다.
