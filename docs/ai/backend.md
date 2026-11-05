# 백엔드 규칙

> 버전: v0.3.0\
> 수정일: 2026-11-05

[`CONTRIBUTING.md`](../../CONTRIBUTING.md)의 공통 기준 위에 백엔드에만 해당하는 규칙을 더합니다.

## 1. 확정한 기술 결정

- Java 17 (Microsoft Build of OpenJDK), Spring Boot 4.1.2, Gradle Kotlin DSL
- 패키징은 JAR, 내장 Tomcat 사용
- DB는 PostgreSQL을 기준으로 하고 MySQL·MariaDB와 호환되게 씁니다([ADR-0013](../adr/0013-shared-db-portable-sql.md)). 관리형 PostgreSQL은 DB 서버로만 씁니다
- Spring Data JPA, Flyway
- Spring Security, Google OAuth2 로그인, 서버 세션 방식. 세션 저장소는 Spring Session 인터페이스 위에 직접 만든 JDBC 구현이며 세션 ID를 해시로만 저장합니다([ADR-0018](../adr/0018-hashed-session-store.md))
- 브라우저는 REST API만 호출하고 DB에 직접 접근하지 않음
- 테스트는 JUnit과 Testcontainers로 실제 DB를 사용합니다. MySQL·MariaDB 드라이버와 Testcontainers 모듈도 함께 둡니다
- 도메인 이벤트 저장소와 경계 검증은 Spring Modulith를 씁니다([ADR-0016](../adr/0016-spring-modulith-events.md)). 외부 메시지 브로커(Kafka 등)는 쓰지 않습니다
- 지표는 Micrometer로 내보내고 Prometheus·Grafana로 봅니다([ADR-0017](../adr/0017-prometheus-grafana.md)). 두 도구는 Compose 선택 프로필입니다
- Spring Session은 `spring-session-core`(인터페이스)만 씁니다. 자동 구성 모듈과 Spring Session JDBC는 넣지 않습니다
- Spring Modulith는 BOM(`spring-modulith-bom`)으로 판을 정합니다. Spring Boot BOM에 들어 있지 않기 때문입니다
- Spring Boot BOM이 정한 판에 알려진 취약점이 있으면 `build.gradle.kts`의 `extra["<이름>.version"]`으로 수정판을 고정하고, 이유는 커밋 메시지와 PR에 적습니다. BOM이 수정판 이상을 정하면 그 줄을 지웁니다
- Lombok, Spotless는 현재 사용하지 않음

이 목록을 바꾸려면 [`docs/adr/`](../adr/README.md)에 ADR을 남기고, 바뀐 내용을 이 문서에 반영합니다. 의존성 추가·변경은 따로 승인을 받습니다.

## 2. 패키지와 계층

최상위 패키지 `reberu` 아래를 core, 고정 기능, 모듈 세 단위로 나눕니다. 전체 패키지 목록, 의존 방향, 도메인 이벤트, 모듈 계약은 [`architecture/modules.md`](../architecture/modules.md)를 따릅니다([ADR-0014](../adr/0014-module-architecture.md)).

| 단위 | 패키지 | 내용 |
| --- | --- | --- |
| core | `reberu.core.*` | 계정, 조직·팀·멤버십, 인증, 권한 판정, 모듈 레지스트리, 도메인 이벤트, 알림 전달, 실시간, 근무일, 활동 기록, 공통 응답·예외·보안 설정 |
| 고정 기능 | `reberu.task`, `reberu.project`, `reberu.todo`, `reberu.report`, `reberu.attendance`, `reberu.progression` | 모든 팀에 늘 있는 업무 기능 |
| 모듈 | `reberu.module.*` | 켜고 끄는 기능(칭찬, 공간, 외부 알림 등) |

- 의존은 모듈 → 고정 기능 → core 한 방향만 허용합니다. 아키텍처 테스트가 이를 검사합니다.

각 단위 안은 계층별로 나눕니다. 필요한 계층만 만듭니다.

```text
team/
├── controller   요청 검증, DTO 변환, HTTP 상태 결정
├── service      유스케이스와 트랜잭션 경계
├── repository   Spring Data JPA 인터페이스
├── entity       JPA 엔티티와 도메인 규칙
├── dto          요청과 응답 record
└── exception    도메인 예외
```

- controller는 repository를 직접 부르지 않습니다.
- 다른 단위의 기능이 필요하면 그 단위 최상위 패키지의 공개 API(조회 인터페이스, 이벤트 레코드)만 씁니다. 다른 단위의 service 구현, repository, entity를 직접 다루지 않습니다.
- 다른 단위의 데이터 변화에 반응할 때는 직접 호출하지 않고 도메인 이벤트를 구독합니다.
- 엔티티는 다른 단위의 엔티티를 연관관계로 가리키지 않고 식별자로만 가리킵니다.
- 비즈니스 규칙은 가능하면 entity 메서드에 둡니다. service는 흐름을 조립합니다.

## 3. JPA와 트랜잭션

- 트랜잭션은 service 메서드에 `@Transactional`로 겁니다. 조회 전용 메서드는 `@Transactional(readOnly = true)`를 씁니다.
- `open-in-view`는 꺼져 있습니다. 지연 로딩이 필요한 데이터는 service 안에서 다 읽고 DTO로 바꿔서 내보냅니다.
- 연관관계는 기본적으로 `FetchType.LAZY`로 둡니다. 목록 조회에서 N+1이 생기면 fetch join이나 `@EntityGraph`로 해결합니다.
- 엔티티에는 public setter를 두지 않습니다. 상태 변경은 의미 있는 이름의 메서드로 합니다.

## 4. DB와 Flyway

- `spring.jpa.hibernate.ddl-auto`는 `validate`로 고정합니다. `update`, `create`로 바꾸지 않습니다.
- 스키마는 Flyway 마이그레이션으로만 바꿉니다. 공통 마이그레이션은 `src/main/resources/db/migration/core`에 둡니다.
- 마이그레이션 파일은 어떤 테이블과 컬럼을 바꿀지 계획을 먼저 보여 주고, 승인을 받은 뒤 작성합니다.
- 이미 적용된 마이그레이션은 수정하지 않고, 새 버전 파일을 추가합니다.
- core와 company의 버전 규칙, 복사한 저장소가 company 위치를 추가하는 방법은 [`CONTRIBUTING.md`의 업스트림 운영 규칙](../../CONTRIBUTING.md#3-업스트림-운영-규칙)을 따릅니다.

## 5. 설정과 비밀값

- 비밀값(DB 비밀번호, OAuth client secret 등)은 `${ENV_NAME}` 형식으로 받고, 설정 파일에 기본값을 두지 않습니다. 값이 없으면 실행이 실패해야 합니다.
- 비밀이 아닌 값(DB URL, 사용자 이름 등)은 로컬 개발용 기본값을 둘 수 있습니다.
- `.env.example`에는 키 이름과 설명만 적고, 값은 비워 둡니다.
- 새 환경 변수를 추가하면 다음 파일을 함께 갱신합니다.
  - `application.yml`
  - `.env.example`
  - 로컬 DB에 필요한 값이면 `compose.yml`
  - `README.md`의 실행 방법 (필요할 때)

## 6. 테스트

- DB가 필요한 테스트는 Testcontainers로 실제 DB를 띄웁니다. H2 같은 임베디드 DB는 쓰지 않습니다. 운영과 같은 DB에서 Flyway 마이그레이션까지 함께 검증하기 위해서입니다.
- 테스트 종류별 기준은 다음과 같습니다.

| 종류 | 도구 | 쓰는 경우 |
| --- | --- | --- |
| 단위 테스트 | JUnit | entity의 도메인 규칙, 순수 계산 로직 |
| 슬라이스 테스트 | `@WebMvcTest` | controller의 요청 검증, 응답 형식, 인증 처리 |
| 저장소 테스트 | `@DataJpaTest` + Testcontainers | 직접 작성한 쿼리, 매핑 |
| 통합 테스트 | `@SpringBootTest` + Testcontainers | 여러 계층을 거치는 유스케이스 |

- Testcontainers 설정은 `TestcontainersConfiguration`을 `@Import`해서 재사용합니다.

## 7. 리뷰 관점

변경한 계층에 맞춰 다음 항목을 확인합니다.

### controller
- 요청 DTO에 Bean Validation이 붙어 있는가
- 엔티티를 그대로 응답하지 않는가
- 상태 코드가 결과와 맞는가 (생성은 201, 없는 자원은 404 등)
- 인증과 권한 확인이 빠지지 않았는가

### service
- 트랜잭션 경계와 `readOnly` 설정이 맞는가
- 다른 사람의 팀이나 업무에 접근하는 경우를 막는가
- 다른 단위의 repository·entity를 직접 쓰지 않는가, 의존 방향(모듈 → 고정 기능 → core)을 지키는가
- 팀 소속 자원을 팀 식별자와 함께 조회하는가

### repository
- 목록 조회에서 N+1이 생기지 않는가
- 쿼리에 문자열을 이어 붙이지 않고 파라미터 바인딩을 쓰는가
- 필요한 인덱스가 마이그레이션에 있는가

### test
- 성공뿐 아니라 실패와 경계 조건도 검증하는가
- 테스트끼리 데이터나 실행 순서에 의존하지 않는가
- 구현 세부가 아니라 관찰 가능한 결과를 검증하는가

## 8. 검증 명령

테스트는 Docker가 실행 중이어야 합니다. `.env`는 필요하지 않습니다.

```bash
# macOS / Linux / Git Bash
./gradlew test
./gradlew bootJar
```

```powershell
# Windows PowerShell
.\gradlew.bat test
.\gradlew.bat bootJar
```

- `JAVA_HOME`이 JDK 17 이상을 가리켜야 Gradle이 실행됩니다. 빌드에 쓰는 JDK 17은 Gradle toolchain이 찾습니다.
- 실패하면 오류 원문을 그대로 보고합니다.
- CI는 `.github/workflows/ci.yml`에서 `main` push와 모든 PR마다 `./gradlew test bootJar`를 실행합니다. Ubuntu 러너의 Docker로 Testcontainers를 띄우고, 실패하면 테스트 리포트를 artifact로 남깁니다.
