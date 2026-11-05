<!--
제목: `type: 요약` (type은 feat, fix, refactor, test, docs, ci, chore 중 하나)
본문은 문장 대신 짧은 명사형으로 씁니다. 해당 없는 절은 지우지 말고 "해당 없음"으로 남깁니다.
-->

## 작업 목적

<!-- 왜 필요한지 한두 줄. 예: 계정을 만들기 전에 약관 동의를 받기 위함 -->

## 변경 내용

<!--
흐름이나 영역별로 묶어 씁니다.
예:
### 로그인 흐름
- 콜백에서 가입 대기 세션 발급
- 동의 API에서 계정 생성
### 저장소
- `account`, `account_consent` 마이그레이션 (V3)
-->

## 보안

<!--
이 PR이 다루는 요구사항·테스트 ID와 공격면 변화. 예:
- 다루는 요구사항: SEC-AUTH-13, TEN-AUTH-02
- 추가한 보안 테스트: T-AUTH-CONSENT
- 새 엔드포인트의 권한 선언: `POST /api/auth/signup/consent` (가입 대기 세션만)
- 공격면 변화: 없음 / 새 입력 필드 2개(길이 상한 적용)
-->

## 테스트

- [ ] 단위 테스트
- [ ] 통합 테스트 (Testcontainers)
- [ ] 보안 테스트 (`docs/architecture/security.md` §15 테스트 ID)
- [ ] 동시성 테스트
- [ ] 직접 호출 확인
- [ ] 테스트 없음 (사유를 아래에)

**실행 결과**

<!-- 실행한 명령과 결과만. 실행하지 않은 검증은 적지 않습니다. 예: `./gradlew test` 통과 (PostgreSQL·MySQL·MariaDB, 42개) -->

## 연관 이슈

<!-- 이슈를 끝내는 PR: Closes #번호 / 여러 PR로 나눈 중간 PR: Related to #번호 -->

## 리뷰 포인트

<!-- 특히 봐 줬으면 하는 곳, 고민한 대안, 남은 작업, 결과 화면이나 링크 -->

## 체크리스트

<!-- 해당 없는 항목은 체크하지 않고 끝에 "— 해당 없음"을 붙입니다. -->

- [ ] 한 가지 목적의 변경만 담음 (`CONTRIBUTING.md`)
- [ ] `./gradlew test`, `./gradlew bootJar` 통과
- [ ] API 변경 시 `docs/architecture/api.md` 반영
- [ ] DB 변경 시 `docs/domain/model.md` 반영, 새 마이그레이션 파일로 추가(적용된 파일 수정 없음), 세 DB 확인
- [ ] 규칙·상태·권한 변경 시 `docs/domain/rules.md`·`docs/architecture/tenancy.md` 반영
- [ ] 보안 규칙 변경 시 `docs/architecture/security.md`와 연결된 보안 테스트 갱신
- [ ] 의존성 변경 시 판·이유·알려진 취약점을 본문에 기록
- [ ] 새 환경 변수 시 `.env.example`과 문서 갱신
- [ ] 비밀값·회사 코드·실제 데이터 없음
- [ ] 고친 문서의 머리말 버전·수정일 올림
- [ ] 깨지는 변경이 있으면 본문에 표시 (복사한 저장소가 고쳐야 받는 변경)
