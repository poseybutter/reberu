# ADR-0013. DB: 공유 테이블, 세 DB 호환, 조직 단위 이관

> 버전: v0.1.0\
> 수정일: 2026-11-04

- 상태: Accepted
- 영향: [architecture/tenancy.md](../architecture/tenancy.md) §11.4·§11.5, [architecture/security.md](../architecture/security.md) §9, [product/overview.md](../product/overview.md) §6·§7, `docs/ai/backend.md` 기술 결정(이슈로 별도 논의)

## 맥락

- 공개 서비스(`MULTI_ORG`)에서는 작은 조직이 많이 생긴다. 조직마다 DB나 스키마를 만들면 연결, 백업, 마이그레이션 비용이 조직 수만큼 는다.
- 처음에는 관리형 PostgreSQL(예: Supabase)로 시작한다.
- 자기 서버에 띄우는 단체(`SINGLE_ORG`)는 이미 쓰는 DB에 올리고 싶어 한다. 단체에 따라 PostgreSQL, MySQL, MariaDB를 쓴다.
- 공개 서비스에서 쓰던 조직 하나를 나중에 그 단체의 서버로 옮겨야 할 수 있다.

## 결정

- 격리 방식은 **DB 하나에 테이블 공유**다. 모든 행은 계정, 조직, 팀 중 하나에 속한다. 조직마다 DB·스키마를 따로 두지 않는다.
- 단체 전용 DB가 필요하면 `SINGLE_ORG` 배포로 해결한다. 코드는 같고 설정만 다르다.
- 지원 DB는 **PostgreSQL을 기준으로 하고 MySQL·MariaDB와 호환**되게 쓴다. 세 DB에서 같게 동작하는 SQL만 쓰고, CI에서 세 DB로 테스트한다(v0.1부터).
- PostgreSQL 행 수준 보안(RLS)은 쓰지 않는다. MySQL·MariaDB에 없기 때문이다. 격리는 앱 코드(TeamContext)와 격리 테스트로 지킨다.
- 관리형 PostgreSQL은 DB 서버로만 쓴다. 그 서비스의 인증, 저장소, 실시간, RLS 정책, 브라우저 직접 접근 기능을 쓰지 않는다.
- 조직 하나의 데이터를 DB와 무관한 형식으로 내보내고 다른 배포에서 가져오는 기능을 둔다(후보 기능). 이를 위해 v0.1부터 데이터 소속 규칙을 지킨다.

## 검토한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 조직마다 스키마 | 조직이 생길 때마다 스키마를 만들고, 마이그레이션을 스키마 수만큼 돌려야 한다 |
| 조직마다 DB | 운영 비용이 조직 수만큼 늘어 누구나 조직을 만드는 서비스에 맞지 않는다 |
| PostgreSQL 전용으로 쓰고 RLS로 격리 | MySQL·MariaDB를 쓰는 단체 배포와 조직 이관이 막힌다 |
| DB마다 다른 SQL을 각각 유지 | 마이그레이션이 세 벌로 갈라져 어긋나기 쉽다. 세 DB 공통 문법이 어려운 곳에만 DB별 파일을 둔다 |

## 결과

- 좋아지는 점: 운영이 단순하다. 단체가 쓰는 DB에 그대로 올릴 수 있다. 조직 하나를 떼어 옮길 수 있다.
- 감수하는 점: DB 전용 기능(RLS, `jsonb`, 배열, `ENUM`, `ON CONFLICT`, 부분 인덱스, 전문 검색)을 쓰지 못한다. 격리는 DB가 아니라 앱이 책임지므로 격리 테스트가 빠지면 안 된다. CI 시간이 세 배로 는다.
- 후속 작업: MySQL·MariaDB 드라이버, Flyway MySQL 모듈, Testcontainers 모듈 추가(의존성 승인 필요). `docs/ai/backend.md` 기술 결정 변경(이슈). ID 저장 타입과 문자열 길이는 도메인 모델에서 정한다.
