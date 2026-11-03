# reberu 작업 규칙

`reberu`는 팀 단위 업무·출석 관리 서비스이며, 레벨업 같은 게임 요소를 포함합니다.
개인 포트폴리오 저장소이고, 이후 다른 조직이 복사해 사용하며 버전 태그로 업데이트를 받을 수 있도록 만듭니다.

## 지켜야 할 규칙

1. 답변, 문서, 커밋 메시지는 한국어로 작성한다.
2. 다른 회사나 다른 저장소의 코드를 복사하지 않는다. 모든 코드는 새로 작성한다.
3. 비밀값은 파일, 커밋, 로그에 쓰지 않는다. `.env`는 Git에 올리지 않는다.
4. push, PR 생성처럼 GitHub 상태를 바꾸는 작업은 실행 전에 확인받는다.
5. 실행하지 않은 검증은 완료라고 하지 않는다. 실패하면 오류를 그대로 보여 준다.
6. 커밋 작성자 이메일이 GitHub noreply 주소인지 확인한다. 회사 메일이면 멈추고 알린다.
7. 커밋에 Co-Authored-By 트레일러를 넣지 않는다.

## 확정한 기술 결정

- Java 17 (Microsoft Build of OpenJDK), Spring Boot 4.1.2, Gradle Kotlin DSL
- 패키징은 JAR, 내장 Tomcat 사용
- PostgreSQL, Spring Data JPA, Flyway
- 운영 스키마는 Flyway로만 변경하고 `ddl-auto`는 `validate`
  - 공통 마이그레이션 위치: `src/main/resources/db/migration/core`
- Spring Security, Google OAuth2 로그인, 서버 세션 방식
- 브라우저는 REST API만 호출하고 DB에 직접 접근하지 않음
- 테스트는 JUnit과 Testcontainers로 실제 PostgreSQL을 사용 (H2 사용 안 함)
- 패키지는 도메인별로 나눔: `auth`, `team`, `project`, `task`, `score`, `global`
