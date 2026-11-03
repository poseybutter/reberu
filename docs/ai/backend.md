# 백엔드 규칙

[`CONTRIBUTING.md`](../../CONTRIBUTING.md)의 공통 기준 위에 백엔드에만 해당하는 규칙을 더합니다.

## 1. 확정한 기술 결정

- Java 17 (Microsoft Build of OpenJDK), Spring Boot 4.1.2, Gradle Kotlin DSL
- 패키징은 JAR, 내장 Tomcat 사용
- PostgreSQL, Spring Data JPA, Flyway
- Spring Security, Google OAuth2 로그인, 서버 세션 방식
- 브라우저는 REST API만 호출하고 DB에 직접 접근하지 않음
- 테스트는 JUnit과 Testcontainers로 실제 PostgreSQL을 사용
- Lombok, Spotless는 현재 사용하지 않음

이 목록을 바꾸려면 먼저 이슈로 논의하고, 바뀐 내용을 이 문서에 반영합니다.

## 2. 패키지와 계층

최상위 패키지 `io.github.poseybutter.reberu` 아래를 도메인별로 나눕니다.

| 패키지 | 내용 |
| --- | --- |
| `auth` | 로그인, 세션, 현재 사용자 |
| `team` | 팀과 팀원 |
| `project` | 프로젝트 |
| `task` | 업무 |
| `score` | 점수, 레벨 같은 게임 요소 |
| `global` | 보안 설정, 전역 예외 처리, 공통 응답처럼 도메인에 속하지 않는 것 |

각 도메인 안은 계층별로 나눕니다. 필요한 계층만 만듭니다.

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
- 다른 도메인의 기능이 필요하면 그 도메인의 service를 부릅니다. 다른 도메인의 repository와 entity 내부를 직접 다루지 않습니다.
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

- DB가 필요한 테스트는 Testcontainers로 실제 PostgreSQL을 띄웁니다. H2 같은 임베디드 DB는 쓰지 않습니다. 운영과 같은 DB에서 Flyway 마이그레이션까지 함께 검증하기 위해서입니다.
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
- 다른 도메인의 repository를 직접 쓰지 않는가

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
