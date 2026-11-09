# API 목록

> 버전: v0.7.0\
> 수정일: 2026-11-09

브라우저가 부르는 REST API의 주소 체계, 공통 형식, v0.1 API 목록을 정한다. 권한 규칙은 [architecture/tenancy.md](tenancy.md) §9·§10, 보안 요구사항은 [architecture/security.md](security.md), 화면은 [product/screens.md](../product/screens.md), 엔티티는 [domain/model.md](../domain/model.md)를 따른다. 실시간 채널은 [architecture/realtime.md](realtime.md)가 맡는다. 요구사항 ID 접두어는 `API`다.

## 1. 범위

- 다룬다: 주소 체계, 요청·응답 형식, 오류, 페이지, 동시성, 중복 요청, v0.1 API 목록(로그인, 계정, 가입, 조직·팀 관리, 모듈 켜고 끄기, 활동 기록, 운영자).
- 다루지 않는다: 필드 하나하나의 검증 규칙(요청 DTO와 [architecture/security.md](security.md) §12.1), 실시간 메시지, v0.2 이후 API(§10에 자리만 둔다).

## 2. 주소 체계

| 접두 | 범위 | 확정하는 것 | 근거 |
| --- | --- | --- | --- |
| `/oauth2/...`, `/login/oauth2/...` | Google 로그인 시작·콜백 | 없음 | [architecture/security.md](security.md) §6.2 |
| `/api/auth/...` | 세션, 가입 대기, 로그아웃 | 세션 상태 | §4 |
| `/api/me/...` | 내 계정과 내 멤버십 | 로그인 계정 | §5 |
| `/api/invite-links/...` | 초대 링크 쓰기 | 로그인 계정 | §6 |
| `/api/public-orgs/...` | 공개 조직 찾기와 가입 요청 | 로그인 계정 | §7 |
| `/api/orgs` | 조직 만들기 | 로그인 계정 | §7 |
| `/api/orgs/{orgId}/...` | 조직 데이터 | 조직 컨텍스트(TeamContext 1~4단계) | §8 |
| `/api/teams/{teamId}/...` | 팀 데이터 | TeamContext 1~10단계 | §9 |
| `/api/orgs/{orgId}/mod/{moduleKey}/...`, `/api/teams/{teamId}/mod/{moduleKey}/...` | 모듈이 여는 API | 위 컨텍스트 + 모듈 켜짐 | §11 |
| `/api/operator/...` | 운영자 | 운영자 계정 | §12 |

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-URL-01 | 경로는 소문자 kebab-case, 자원은 복수 명사다. 경로의 식별자는 UUID다(TEN-ISO-09) | 필수 |
| API-URL-02 | 팀 데이터는 `/api/teams/{teamId}` 아래, 조직 데이터는 `/api/orgs/{orgId}` 아래에만 둔다(TEN-ISO-01). 팀 경로에 `orgId`를 함께 넣지 않는다. 조직은 서버가 팀에서 정한다(TeamContext 2단계) | 필수 |
| API-URL-03 | 요청 본문과 쿼리에 `orgId`, `teamId`, `role`, `orgAdmin`, `accountId`(본인을 뜻하는 값)를 받지 않는다. 대상 계정은 경로로만 받는다(TEN-TST-07, SEC-AUTHZ-06) | 필수 |
| API-URL-04 | 상태 전이(승인, 거절, 정지, 보관, 내보내기 등)는 행동마다 전용 `POST .../{행동}` 경로로 한다. 일반 수정(`PATCH`)으로 상태·역할·관리자 여부·소속을 바꾸지 않는다(SEC-AUTHZ-07) | 필수 |
| API-URL-05 | `GET`은 상태를 바꾸지 않는다. 상태를 바꾸는 요청은 `POST`, `PUT`, `PATCH`, `DELETE`만 쓰고 CSRF 헤더를 요구한다(SEC-SESS-09) | 필수 |
| API-URL-06 | 비밀값과 토큰은 경로와 쿼리에 넣지 않는다. 초대 링크 토큰은 링크 주소의 `#` 뒤(서버와 Referer에 가지 않음)에 두고, 화면이 요청 본문으로 보낸다(§6) | 필수 |

## 3. 공통 형식

### 3.1 요청과 응답

| 항목 | 규칙 |
| --- | --- |
| 본문 | JSON, UTF-8. 필드 이름은 camelCase |
| 시각 | `UTC_TIMESTAMP`는 ISO-8601 UTC(`2026-11-05T01:02:03.123456Z`). 조직 시간대 변환은 화면이 한다 |
| 날짜 | `LOCAL_DATE`는 `YYYY-MM-DD`(조직 시간대 기준 날짜) |
| 코드 값 | 대문자 상수(`ACTIVE`, `TEAM_ADMIN`) |
| 빈 값 | 값이 없는 필드는 `null`로 보낸다. 필드를 빼지 않는다 |
| 성공 | 조회 `200`, 만들기 `201`(본문에 만든 자원), 본문 없는 행동 `204` |

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-FMT-01 | 요청 DTO에 없는 필드, 형식이 틀린 JSON, 지원하지 않는 `Content-Type`은 `400 INVALID_REQUEST`로 거부한다(SEC-AUTHZ-06) | 필수 |
| API-FMT-02 | 응답은 API마다 정한 응답 DTO로만 만든다. 엔티티를 그대로 직렬화하지 않는다 | 필수 |
| API-FMT-03 | 다른 사람의 이메일은 관리 API(§8 구성원·초대, §9 구성원·초대)의 응답에만 담는다(SEC-OUTPUT-04) | 필수 |
| API-FMT-04 | 인증이 필요한 모든 응답에 `Cache-Control: no-store`를 붙인다(SEC-HDR-07) | 필수 |
| API-FMT-05 | 모든 API 응답에 `X-App-Version`(지금 배포된 화면 판)과 `X-App-Min-Version`(받아 주는 가장 낮은 화면 판)을 담는다. API는 바로 이전 판의 화면이 부르는 형태를 깨지 않게 바꾼다([architecture/frontend.md](frontend.md) §8.2). 값은 배포 설정 `APP_VERSION`, `APP_MIN_VERSION`이고 기본값은 `0.0.0`이다 | 필수 |
| API-FMT-06 | `/api`, `/oauth2`, `/login/oauth2` 밖의 모든 `GET` 주소에는 같은 `index.html`을 돌려준다. 없는 API 주소는 `index.html`이 아니라 `404 NOT_FOUND` JSON이다(FE-ROUTE-05) | 필수 |

### 3.2 오류

오류 본문은 `{ code, message, traceId }`이며, `400 INVALID_REQUEST`만 칸별 오류 `fields`를 더 담는다. 코드는 [architecture/security.md](security.md) §12.2 표의 것만 쓴다(SEC-OUTPUT-05).

```json
{
  "code": "INVALID_REQUEST",
  "message": "입력 값을 확인해 주세요",
  "traceId": "7f3c2a9e1b4d4c0a",
  "fields": [{ "field": "name", "reason": "TOO_LONG" }]
}
```

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-ERR-01 | `message`는 사용자에게 보여 줄 짧은 문장이다. 예외 메시지, 스택, SQL, 클래스 이름, 다른 조직·팀의 존재를 담지 않는다(SEC-OUTPUT-03) | 필수 |
| API-ERR-02 | `fields[].reason`은 정해진 코드(`REQUIRED`, `TOO_LONG`, `TOO_SHORT`, `INVALID_FORMAT`, `NOT_ALLOWED_VALUE`, `BANNED_WORD`, `DUPLICATE`)만 쓴다. `DUPLICATE`는 유일해야 하는 값(팀 이름 등)이 이미 있을 때다. 입력 값을 되돌려 보내지 않는다 | 필수 |
| API-ERR-03 | 볼 수 없는 자원에 대한 요청은 검증 오류보다 먼저 `404 NOT_FOUND`로 끝낸다. 남의 자원에 잘못된 본문을 보내 `400`과 `404`의 차이로 존재를 알아낼 수 없게 한다 | 필수 |
| API-ERR-04 | 경로에 없는 메서드로 부르면 `405`가 아니라 `404 NOT_FOUND`다. 경로가 있는지 알려 주지 않는다 | 필수 |
| API-ERR-05 | `fields`는 칸마다 사유 하나만 담는다. 여러 규칙을 함께 어기면 `fields[].reason` 목록의 앞쪽 사유(`REQUIRED`가 가장 앞)를 쓴다. 칸과 무관한 `400`(깨진 JSON, DTO에 없는 필드 등)은 빈 `fields`다 | 필수 |

### 3.3 페이지

| 항목 | 규칙 |
| --- | --- |
| 방식 | 커서 방식. 쿼리 `cursor`(앞 응답의 `nextCursor`), `limit`(기본 20, 최대 100) |
| 응답 | `{ "items": [...], "nextCursor": "..." }`. 마지막 쪽이면 `nextCursor`는 `null` |
| 정렬 | API마다 정한 허용 목록에서만 고른다(SEC-INPUT-02). 기본은 최신순 |
| 걸러 내기 | 지금 조직·팀으로 먼저 거른 뒤 정렬·페이지를 적용한다(TEN-ISO-10) |

- API-PAGE-01 (필수): 커서는 서버가 서명한 불투명 값이다. 화면이 커서를 만들거나 고치면 `400 INVALID_REQUEST`로 거부한다. 커서는 그 커서를 만든 조직·팀 경로에서만 유효하다.

### 3.4 동시성과 중복 요청

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-CON-01 | 잠금(`version`)이 있는 자원의 수정 요청은 본문에 읽은 `version`을 담는다. 다르면 `409 VERSION_CONFLICT`이고 화면은 최신 값을 다시 불러온다 | 필수 |
| API-CON-02 | 이미 다른 사람이 처리한 승인·거절·취소·수락에는 `409 ALREADY_PROCESSED`로 응답한다(UX-SCR-012). 같은 사람이 같은 행동을 다시 보내도 같다 | 필수 |
| API-CON-03 | 자원을 만드는 `POST`(조직, 팀, 초대, 초대 링크, 직무 유형)는 `Idempotency-Key` 헤더(UUID)를 받는다. 같은 계정·같은 키의 두 번째 요청은 처음 응답을 그대로 돌려주고 다시 만들지 않는다(초대 링크는 API-CON-05를 따른다). 키에 다른 메서드·경로·본문을 보내면 `400 INVALID_REQUEST`다. 키가 없거나 UUID가 아니면 `400 INVALID_REQUEST`(`fields`의 `Idempotency-Key`)다. 성공 응답(2xx)만 기록하고 24시간 지나면 지운다 | 필수 |
| API-CON-05 | 토큰 원문이 담긴 응답(초대 링크 만들기)은 중복 요청 기록에 저장하지 않는다. 같은 키의 두 번째 요청에는 `409 ALREADY_PROCESSED`로 응답하고 토큰을 다시 보내지 않는다(TEN-LINK-04) | 필수 |
| API-CON-04 | 마지막 관리자를 없애는 요청은 `409 LAST_ADMIN_REQUIRED`로 거부한다(TEN-INV-20). 화면은 이 코드로 "관리자가 한 명은 있어야 해요"를 보인다 | 필수 |

### 3.5 권한 표기

아래 표의 "권한" 칸은 다음 뜻이다. 권한 선언은 모든 API에 있어야 하고, 없으면 서버가 시작하지 않는다(SEC-AUTHZ-01·02).

| 표기 | 뜻 |
| --- | --- |
| 공개 | 세션이 없어도 된다 |
| 가입 대기 | 약관 동의 전의 가입 대기 세션만(TEN-AUTH-02) |
| 계정 | `ACTIVE` 계정의 로그인 세션 |
| 조직 멤버 | 조직 컨텍스트 통과. 손님은 표에 "손님 가능"이 있는 것만 |
| 조직 관리자 | 그 조직의 조직 관리자 |
| 팀 멤버 | TeamContext 통과(그 팀 멤버십, 또는 조직 관리자) |
| 팀 관리자 | 그 팀의 `TEAM_ADMIN` 또는 조직 관리자 |
| 운영자 | 배포 설정(환경 변수)으로 정한 운영자 계정 |
| 🔒 | 최근 인증 필요(SEC-SESS-10). 지났으면 `401 REAUTH_REQUIRED` |
| 제한 | 적용하는 요청 제한 ID([architecture/security.md](security.md) §13.2). 표에 없으면 SEC-RATE-08 |

## 4. 로그인과 세션

| 메서드 | 경로 | 하는 일 | 권한 | 비고 |
| --- | --- | --- | --- | --- |
| `GET` | `/oauth2/authorization/google` | Google 인가 요청으로 보낸다. 쿼리 `next`는 서버의 허용 경로 목록에 있을 때만 저장하고, 아니면 버린다(SEC-AUTH-08). `reauth=true`이거나 이미 로그인한 세션이면 재인증으로 보고 `prompt=login`, `max_age=0`을 붙인다 | 공개 | 제한 SEC-RATE-01 |
| `GET` | `/login/oauth2/code/google` | 콜백. 판정은 [architecture/tenancy.md](tenancy.md) §6.1. 결과에 따라 A6, A1, 첫 화면으로 `302` | 공개 | 제한 SEC-RATE-01 |
| `GET` | `/api/auth/session` | 세션 상태 `ANONYMOUS`·`SIGNUP_PENDING`·`AUTHENTICATED`, 배포 모드 `deploymentMode`, 로그인 뒤 갈 첫 화면 `next`(§5.1, `AUTHENTICATED`일 때만, `GET /api/me/home`과 같은 판정, TEN-HOME-03). 로그인 상태면 계정 ID와 인증 시각도 담는다(탭 계정 확인 FE-TAB-03, 재인증 판단 FE-REAUTH-01). CSRF 쿠키를 내려 준다 | 공개 | |
| `GET` | `/api/auth/signup` | A6에 보일 값: Google이 준 이메일·이름·사진, 지금 약관·처리방침 버전 | 가입 대기 | |
| `POST` | `/api/auth/signup/consent` | 동의. 본문 `termsVersion`, `privacyVersion`(지금 버전과 같아야 함), 선택 `inviteToken`. 계정을 만들고 세션 ID를 새로 발급한다. 링크가 있으면 §6과 같이 처리한다 | 가입 대기 | `201`. 응답에 첫 화면 |
| `DELETE` | `/api/auth/signup` | 동의 안 함. 가입 대기 세션을 지운다 | 가입 대기 | `204` |
| `POST` | `/api/auth/logout` | 서버 세션 삭제, 실시간 연결 종료, 쿠키 만료(SEC-SESS-08) | 계정 | `204` |

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-AUTH-01 | 가입 대기 세션으로 위 표의 가입 대기 API 밖을 부르면 `401 CONSENT_REQUIRED`다(TEN-AUTH-02) | 필수 |
| API-AUTH-02 | 콜백의 모든 실패는 같은 `302` 대상(A1 실패)으로 보낸다. 정지된 계정만 A1 정지 안내로 보낸다(TEN-AUTH-06, SEC-AUTH-11) | 필수 |
| API-AUTH-03 | 재인증 콜백에서 `sub`가 세션 계정과 다르면 세션을 지우고 A1로 보낸다([architecture/security.md](security.md) §7.2) | 필수 |

## 5. 내 계정과 멤버십

### 5.1 첫 화면 값

`GET /api/auth/session`과 `GET /api/me/home`의 `next`는 아래 값 가운데 하나이며, [architecture/tenancy.md](tenancy.md) §6.1·§6.2의 판정을 따른다.

| `next.screen` | 함께 오는 값 |
| --- | --- |
| `TEAM_HOME` | `teamId`. 화면은 그 팀의 공간 모듈 켜짐에 따라 B1 또는 B7을 연다 |
| `TEAM_PICKER` | 없음(A3) |
| `JOIN_PENDING` | 없음(A2) |
| `START` | 없음(A0) |

### 5.2 API

| 메서드 | 경로 | 하는 일 | 권한 | 비고 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/me` | 내 계정: 표시 이름, 이메일, 사진 주소, 기본 아바타, 계정 종류(`WORKSPACE`·`PERSONAL`, TEN-ACC-01), 동의한 약관 버전 | 계정 | D3 |
| `PATCH` | `/api/me` | 표시 이름 바꾸기. 본문 `displayName`, `version`. 금지어 검사(SEC-ORG-13) | 계정 | D3 |
| `GET` | `/api/me/home` | 첫 화면 판정(`next`), 내 조직 목록과 조직마다 내 팀 목록(조직 이름, 팀 이름, 직무 유형 이름, 내 역할, 손님 표시와 만료 시각, 팀 상태), 받은 초대 수 | 계정 | A0, A3 |
| `GET` | `/api/me/invitations` | 내 확인된 이메일과 같은 `PENDING` 초대: 조직 이름, 팀 이름, 초대한 사람 표시 이름, 멤버 종류, 수락 기한 | 계정 | 초대 카드(UX-SCR-024) |
| `POST` | `/api/me/invitations/{invitationId}/accept` | 수락. 조건은 SEC-TOKEN-08·10, 처리는 TEN-AUTH-10~14 | 계정 | 응답에 `next` |
| `POST` | `/api/me/invitations/{invitationId}/decline` | 거절 → `DECLINED` | 계정 | `204` |
| `GET` | `/api/me/join-requests` | 내 `REQUESTED`·`REJECTED` 조직·팀 멤버십: 대상 이름, 요청 시각, 상태, 다시 요청할 수 있는 시각(TEN-REQ-02) | 계정 | A2, A5 |
| `POST` | `/api/me/org-requests/{orgId}/cancel` | 내 조직 가입 요청 취소 → `LEFT`(사유 `SELF`) | 계정 | `204` |
| `POST` | `/api/me/team-requests/{teamId}/cancel` | 내 팀 가입 요청 취소 | 계정 | `204` |
| `POST` | `/api/me/orgs/{orgId}/leave` | 조직 나가기([architecture/tenancy.md](tenancy.md) §14.2) | 계정 | `409 LAST_ADMIN_REQUIRED` |
| `POST` | `/api/me/teams/{teamId}/leave` | 팀 나가기 | 계정 | `409 LAST_ADMIN_REQUIRED` |
| `GET` | `/api/me/withdrawal-check` | 탈퇴 전 판정: 관리자를 넘겨야 하는 조직·팀 목록, 함께 보관될 혼자뿐인 조직·팀 목록([architecture/tenancy.md](tenancy.md) §5.1) | 계정 | D3 |
| `POST` | `/api/me/withdraw` | 탈퇴. 본문 `confirmText`(정한 문구), 보관될 조직마다 `orgNameConfirmations`. 판정과 처리는 한 트랜잭션 | 계정 🔒 | `204`, 세션 삭제 |

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-ME-01 | `/api/me/...`의 경로 식별자는 본인 멤버십·초대를 찾는 데만 쓴다. 본인 것이 아니면 `404 NOT_FOUND`다 | 필수 |
| API-ME-02 | 탈퇴 요청의 확인 값이 `withdrawal-check` 이후 바뀐 상태와 맞지 않으면(그 사이 누가 들어옴 등) 탈퇴하지 않고 `409 ALREADY_PROCESSED`로 다시 판정하게 한다 | 필수 |

## 6. 초대 링크 쓰기

링크 주소는 `https://<배포 호스트>/join#<토큰>`이다. 화면은 `#` 뒤의 토큰을 읽어 아래 API로 보낸다. 로그인하지 않았으면 화면이 토큰을 브라우저 탭 저장소(`sessionStorage`)에 두고 로그인한 뒤 다시 보낸다.

| 메서드 | 경로 | 하는 일 | 권한 | 비고 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/invite-links/redeem` | 본문 `token`. 판정은 [architecture/tenancy.md](tenancy.md) §7.4. 결과 `JOINED`(팀 `ACTIVE`), `REQUESTED`(승인 대기), `ALREADY_MEMBER` 가운데 하나와 `next` | 계정 | 제한 SEC-RATE-02 |

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-LINK-01 | 실패는 사유와 관계없이 `404 NOT_FOUND` 하나다. 본문, 헤더, 응답 시간으로 사유를 구별할 수 없다(TEN-LINK-05, SEC-TOKEN-06, SEC-ORG-02) | 필수 |
| API-LINK-02 | 로그인하지 않은 요청은 토큰을 보지 않고 `401 UNAUTHENTICATED`로 끝낸다. 로그인 여부에 따라 토큰 판정 결과가 드러나지 않는다 | 필수 |
| API-LINK-03 | 성공 응답에 조직·팀 이름을 담는 것은 `JOINED`·`ALREADY_MEMBER`일 때만이다. `REQUESTED`는 A2가 `/api/me/join-requests`로 다시 불러온다 | 필수 |

## 7. 조직 만들기와 공개 조직 (`MULTI_ORG`)

`SINGLE_ORG`에서는 이 절의 API가 모두 `404 NOT_FOUND`다(TEN-MODE-02).

| 메서드 | 경로 | 하는 일 | 권한 | 비고 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/orgs` | 조직 만들기. 본문 `name`, `tagline`, `visibility`, `timeZone`, `firstTeam { name, jobTypeKey }`(`jobTypeKey`는 기본 직무 유형, [architecture/tenancy.md](tenancy.md) §12). 처리는 [architecture/tenancy.md](tenancy.md) §8.1. 응답 `{ orgId, teamId }` | 계정 | `201`, 제한 SEC-RATE-05, `409 LIMIT_EXCEEDED`, `Idempotency-Key` |
| `GET` | `/api/default-job-types` | 조직 만들기에서 고를 기본 직무 유형 목록(key, 이름, 설명) | 계정 | A4 |
| `GET` | `/api/public-orgs?q=` | 공개 조직 찾기. 두 글자 이상, 최대 20개, 이름·한 줄 소개·가입 방식만(SEC-ORG-03). `PUBLIC`·`ACTIVE`이고 가입 요청이 켜진 조직만 | 계정 | 제한 SEC-RATE-06 |
| `POST` | `/api/public-orgs/{orgId}/join` | 가입 요청 또는 바로 가입. 결과 `REQUESTED` 또는 `JOINED` | 계정 | 제한 SEC-RATE-04, 재요청 대기 중이면 `409 REQUEST_COOLDOWN` |

- API-ORG-01 (필수): `public-orgs/{orgId}/join`은 조직이 `PRIVATE`이거나 가입 요청이 꺼졌거나 없을 때 모두 같은 `404 NOT_FOUND`다(SEC-ORG-01).

## 8. 조직 관리 (`/api/orgs/{orgId}`)

| 메서드 | 경로 | 하는 일 | 권한 | 비고 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/orgs/{orgId}` | 조직 이름, 시간대, 상태, 내 멤버 종류와 조직 관리자 여부 | 조직 멤버(손님 가능) | |
| `GET` | `/api/orgs/{orgId}/overview` | 팀 수와 팀별 인원, 가입 요청·초대 승인 대기 수, 최근 조직 활동 5개 | 조직 관리자 | F1. 출석 수는 v0.2 |
| `GET` | `/api/orgs/{orgId}/settings` | 조직 설정 전체와 `version` | 조직 관리자 | F7 |
| `PATCH` | `/api/orgs/{orgId}/settings` | 이름, 한 줄 소개, 시간대(OQ-32), 공개 범위, 가입 방법, 새 구성원 조직 관리자 확인(TEN-ADM-01·02), 손님 규칙, 초대 수락 기한 | 조직 관리자 🔒 | `version` 필수 |
| `POST` | `/api/orgs/{orgId}/archive` | 조직 보관([architecture/tenancy.md](tenancy.md) §14.1) | 조직 관리자 🔒 | |
| `POST` | `/api/orgs/{orgId}/restore` | 조직 복원. 관리자 없는 보관 조직은 `404 NOT_FOUND` | 조직 관리자 🔒 | |
| `GET` | `/api/orgs/{orgId}/teams` | 조직 관리자: 모든 팀(F2). 구성원: `PUBLIC` 팀과 내 팀(A3 공개 팀 찾기). 항목마다 `id`, `name`, `visibility`, `state`, `jobTypeName`, `memberCount`, `myRole`, `createdAt`. 만든 순서, 커서 페이지(§3.3) | 조직 멤버 | 손님은 `404` |
| `POST` | `/api/orgs/{orgId}/teams` | 팀 만들기. 본문 `name`, `visibility`, `jobTypeId`, `firstAdminAccountId`(TEN-TEAM-01). 응답 `{ teamId }` | 조직 관리자 | `201`, `Idempotency-Key`, 이름 중복 `400`(`fields`의 `DUPLICATE`) |
| `POST` | `/api/orgs/{orgId}/teams/{teamId}/archive` | 팀 보관(TEN-TEAM-04·05) | 조직 관리자 🔒 | `204` |
| `POST` | `/api/orgs/{orgId}/teams/{teamId}/restore` | 팀 복원. 본문 `newAdminAccountId`는 팀 관리자가 없을 때만 필수이고, 있을 때 보내면 `400`(TEN-TEAM-02·03) | 조직 관리자 🔒 | `204` |
| `POST` | `/api/orgs/{orgId}/teams/{teamId}/join` | 공개 팀 가입 요청 또는 바로 들어가기([architecture/tenancy.md](tenancy.md) §7.5) | 조직 멤버 | 구성원만, 제한 SEC-RATE-04 |
| `GET` | `/api/orgs/{orgId}/job-types` | 직무 유형 목록(숨김 포함 여부 쿼리) | 조직 관리자 | F3 |
| `POST` | `/api/orgs/{orgId}/job-types` | 직무 유형 추가 | 조직 관리자 | `201`, `Idempotency-Key` |
| `PATCH` | `/api/orgs/{orgId}/job-types/{jobTypeId}` | 직무 유형 고치기. 이미 만든 팀은 바뀌지 않는다 | 조직 관리자 | `version` |
| `POST` | `/api/orgs/{orgId}/job-types/{jobTypeId}/hide`, `/unhide` | 숨기기, 다시 보이기 | 조직 관리자 | |
| `GET` | `/api/orgs/{orgId}/members` | 구성원·손님 목록. 쿼리 `state`, `kind`, `teamId` | 조직 관리자 | F5 |
| `POST` | `/api/orgs/{orgId}/members/{accountId}/suspend`, `/unsuspend` | 조직 멤버십 정지·해제 | 조직 관리자 🔒 | 마지막 관리자 `409` |
| `POST` | `/api/orgs/{orgId}/members/{accountId}/remove` | 조직에서 내보내기 | 조직 관리자 🔒 | 마지막 관리자 `409` |
| `POST` | `/api/orgs/{orgId}/members/{accountId}/grant-org-admin`, `/revoke-org-admin` | 조직 관리자 지정·해제. 손님은 지정 불가(TEN-INV-07) | 조직 관리자 🔒 | 지정 직후 대상의 세션 ID 재발급(SEC-SESS-03) |
| `POST` | `/api/orgs/{orgId}/members/{accountId}/convert-to-member` | 손님을 구성원으로 바꾸기(TEN-GUEST-07) | 조직 관리자 🔒 | |
| `POST` | `/api/orgs/{orgId}/members/{accountId}/extend-guest` | 손님 이용 기간 연장. 본문 `teamId`, `days`(상한 TEN-GUEST-03) | 조직 관리자 🔒 | 연장 요청 API는 없다. 팀 관리자는 조직 관리자에게 직접 알린다 |
| `GET` | `/api/orgs/{orgId}/join-requests` | 조직 가입 요청과 팀 관리자 링크로 온 새 구성원 확인 대기(TEN-ADM-01) | 조직 관리자 | F5 |
| `POST` | `/api/orgs/{orgId}/join-requests/{accountId}/approve` | 승인. 선택 `teamId`, `role`(`TEAM_MEMBER`·`VIEWER`) | 조직 관리자 | `409 ALREADY_PROCESSED` |
| `POST` | `/api/orgs/{orgId}/join-requests/{accountId}/reject` | 거절 | 조직 관리자 | |
| `GET` | `/api/orgs/{orgId}/invitations` | 모든 팀의 이메일 초대. 쿼리 `state`, `kind`, `teamId` | 조직 관리자 | F5 |
| `POST` | `/api/orgs/{orgId}/invitations` | 이메일 초대 만들기. 본문 `emails`(최대 50), `kind`, `teamId`(손님은 필수), `role`, `guestDays` | 조직 관리자 | `201`, 제한 SEC-RATE-03, `Idempotency-Key` |
| `POST` | `/api/orgs/{orgId}/invitations/{invitationId}/approve`, `/reject` | `PENDING_APPROVAL` 초대 승인·거절(손님 초대, 팀 관리자의 새 구성원 초대) | 조직 관리자 🔒 | |
| `POST` | `/api/orgs/{orgId}/invitations/{invitationId}/revoke` | 초대 취소 → `REVOKED` | 조직 관리자 | |
| `GET` | `/api/orgs/{orgId}/modules` | 설치된 모듈: key, 이름, 설명, 범위, 출처, 허용·켜짐, 켠 팀 수, 필요한 모듈 | 조직 관리자 | F4 |
| `GET` | `/api/orgs/{orgId}/modules/{moduleKey}/impact` | 허용을 거두거나 끌 때 영향: 꺼질 팀 수, 함께 꺼질 모듈 | 조직 관리자 | TEN-MOD-04 확인 화면 |
| `PUT` | `/api/orgs/{orgId}/modules/{moduleKey}/allowed` | `TEAM` 모듈 허용·거두기. 본문 `allowed` | 조직 관리자 🔒 | TEN-MOD-03 |
| `PUT` | `/api/orgs/{orgId}/modules/{moduleKey}/enabled` | `ORG` 모듈 켜기·끄기. 본문 `enabled` | 조직 관리자 🔒 | TEN-MOD-04 |
| `GET` | `/api/orgs/{orgId}/activity` | 조직 활동 기록. 쿼리 `from`, `to`, `actorId`, `action`, `teamId` | 조직 관리자 | F6 |

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-OADM-01 | 경로의 `accountId`, `teamId`, `invitationId`, `jobTypeId`는 늘 경로의 `orgId`와 함께 찾는다. 다른 조직의 것이면 `404 NOT_FOUND`다(TEN-ISO-02) | 필수 |
| API-OADM-02 | 조직 관리자가 팀 API(§9)를 팀 멤버십 없이 부르면 그 열람을 조직 활동 기록에 남긴다(TEN-PERM-04). 한 사람이 같은 팀을 연속으로 열면 30분에 한 번만 남긴다 | 필수 |
| API-OADM-03 | 설정 응답과 목록 응답에 토큰 해시, 세션 정보, 웹훅 주소 원문을 담지 않는다(TEN-LOG-03) | 필수 |

## 9. 팀 관리 (`/api/teams/{teamId}`)

| 메서드 | 경로 | 하는 일 | 권한 | 비고 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/teams/{teamId}` | 팀 이름, 상태, 공개 범위, 내 역할(조직 관리자 열람이면 `null`), 켜진 모듈 key 목록과 메뉴 | 팀 멤버 | 화면 공통 |
| `GET` | `/api/teams/{teamId}/overview` | 팀 가입 요청 대기 수, 손님 만료 임박 수, 최근 팀 활동 5개 | 팀 관리자 | E1. 출석 수는 v0.2 |
| `GET` | `/api/teams/{teamId}/settings` | 팀 설정과 `version` | 팀 관리자 | E4 |
| `PATCH` | `/api/teams/{teamId}/settings` | 이름(조직 안 유일), 공개 범위, 공개 팀 가입 방식, 리포트 주기 값 | 팀 관리자 | `version` 필수 |
| `GET`, `POST` | `/api/teams/{teamId}/labels` | 업무 구분 라벨 목록, 추가 | 팀 관리자(목록은 팀 멤버) | E4 |
| `PATCH` | `/api/teams/{teamId}/labels/{labelId}` | 이름·순서·숨김 | 팀 관리자 | |
| `GET`, `POST` | `/api/teams/{teamId}/custom-fields` | 업무·프로젝트 추가 항목 목록, 추가 | 팀 관리자(목록은 팀 멤버) | E4 |
| `PATCH` | `/api/teams/{teamId}/custom-fields/{fieldId}` | 이름·필수·순서·숨김 | 팀 관리자 | |
| `GET` | `/api/teams/{teamId}/members` | 팀원 목록: 표시 이름, 이메일(팀 관리자에게만), 멤버 종류, 역할, 상태, 들어온 날, 손님 만료 시각 | 팀 멤버 | E3. 팀원·읽기 전용에게는 이메일 없음 |
| `POST` | `/api/teams/{teamId}/members` | 바로 추가. 본문 `accountId`(그 조직 `ACTIVE` 구성원), `role` | 팀 관리자 | E2 |
| `PUT` | `/api/teams/{teamId}/members/{accountId}/role` | 팀 역할 바꾸기. 본문 `role`, `version`. 손님은 `TEAM_ADMIN` 불가 | 팀 관리자 🔒 | 마지막 관리자 `409` |
| `POST` | `/api/teams/{teamId}/members/{accountId}/suspend`, `/unsuspend` | 팀 멤버십 정지·해제 | 팀 관리자 🔒 | 마지막 관리자 `409` |
| `POST` | `/api/teams/{teamId}/members/{accountId}/remove` | 팀에서 내보내기 | 팀 관리자 🔒 | 마지막 관리자 `409` |
| `GET` | `/api/teams/{teamId}/join-requests` | 팀 가입 요청(공개 팀 요청, 승인 필요 링크) | 팀 관리자 | E2 |
| `POST` | `/api/teams/{teamId}/join-requests/{accountId}/approve`, `/reject` | 승인·거절. 조직 멤버십이 확인 대기면 승인할 수 없다(TEN-ADM-01) | 팀 관리자 | `409 ALREADY_PROCESSED` |
| `GET` | `/api/teams/{teamId}/invitations` | 이 팀의 이메일 초대 | 팀 관리자 | E2 |
| `POST` | `/api/teams/{teamId}/invitations` | 이 팀 초대 만들기. 본문 `emails`, `kind`, `role`, `guestDays`. 상태는 [architecture/tenancy.md](tenancy.md) §5.6 | 팀 관리자 | `201`, 제한 SEC-RATE-03, `Idempotency-Key` |
| `POST` | `/api/teams/{teamId}/invitations/{invitationId}/revoke` | 초대 취소 | 팀 관리자 | |
| `GET` | `/api/teams/{teamId}/invite-links` | 링크 목록: 승인 필요, 역할, 만료 시각, 사용 횟수/상한, 켜짐, 만든 사람. 토큰 없음 | 팀 관리자 | E2 |
| `POST` | `/api/teams/{teamId}/invite-links` | 링크 만들기. 본문 `approvalRequired`, `role`, `expiresInDays`(기본 7, 최대 30), `maxUses`(필수). 응답에만 링크 주소가 한 번 들어 있다(TEN-LINK-04) | 팀 관리자 | `201`, 조직이 링크를 껐으면 `404`, `Idempotency-Key` |
| `POST` | `/api/teams/{teamId}/invite-links/{linkId}/disable` | 링크 끄기. 다시 켜는 API는 없다. 새로 만든다 | 팀 관리자 | |
| `GET` | `/api/teams/{teamId}/modules` | 조직이 허용한 `TEAM` 모듈과 켜짐 | 팀 관리자 | E6 |
| `GET` | `/api/teams/{teamId}/modules/{moduleKey}/impact` | 끌 때 함께 꺼질 모듈, 켤 때 먼저 켜야 할 모듈 | 팀 관리자 | |
| `PUT` | `/api/teams/{teamId}/modules/{moduleKey}/enabled` | 켜기·끄기. 본문 `enabled`, `confirmCascade`(함께 꺼지는 모듈이 있으면 참이어야 함) | 팀 관리자 | TEN-MOD-04·05 |
| `GET` | `/api/teams/{teamId}/activity` | 팀 활동 기록. 쿼리 `from`, `to`, `actorId`, `action` | 팀 관리자 | E5 |

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-TADM-01 | 팀 API는 [architecture/tenancy.md](tenancy.md) §10의 순서로만 판정한다. 1~8단계 실패는 같은 `404`, 쓰기인데 보관 상태면 `409 READ_ONLY`, 볼 수 있는데 권한이 없으면 `403 FORBIDDEN_ACTION`이다 | 필수 |
| API-TADM-02 | 팀원·읽기 전용이 관리 API(§9 표에서 권한이 팀 관리자인 것)를 부르면 `404 NOT_FOUND`다. 관리 자원의 존재를 드러내지 않는다([architecture/security.md](security.md) §9.2 H단계) | 필수 |
| API-TADM-03 | 경로의 `accountId`, `linkId`, `invitationId`, `labelId`, `fieldId`는 늘 경로의 `teamId`와 함께 찾는다(TEN-ISO-02) | 필수 |

## 10. 이후 단계의 자리

v0.2 이후 API는 그 단계를 시작할 때 이 문서에 더한다. 주소는 아래 자리를 쓴다.

| 단계 | 자리 |
| --- | --- |
| v0.2 | `/api/teams/{teamId}/projects`, `/tasks`, `/todos`, `/attendance`, `/progress`, `/api/orgs/{orgId}/holidays` |
| v0.3 | `/api/teams/{teamId}/notifications`, `/reports`, `/api/me/notification-prefs`, 실시간 연결 주소([architecture/realtime.md](realtime.md)), 모듈 `praise`·`outbound` |
| v0.4 이후 | 모듈 API(§11) |

## 11. 모듈 API 규칙

| ID | 요구사항 | 수준 |
| --- | --- | --- |
| API-MOD-01 | 모듈이 여는 API는 `/api/teams/{teamId}/mod/{moduleKey}/...`(`TEAM` 범위) 또는 `/api/orgs/{orgId}/mod/{moduleKey}/...`(`ORG` 범위) 아래에만 둔다. 고정 기능·core 경로와 겹치지 않는다 | 필수 |
| API-MOD-02 | 모듈 API는 모듈 key와 필요 권한을 선언한다. 경로의 `moduleKey`와 선언한 key가 다르면 서버가 시작하지 않는다(ARCH-MOD-01) | 필수 |
| API-MOD-03 | 모듈이 꺼졌거나 설치되지 않았으면 `404 NOT_FOUND`다. 켜진 팀과 같은 응답 형식을 쓰지 않는다(TEN-MOD-01, ARCH-MOD-04) | 필수 |
| API-MOD-04 | 모듈 API도 §3의 형식·오류·페이지·동시성 규칙을 따른다 | 필수 |

## 12. 운영자

| 메서드 | 경로 | 하는 일 | 권한 | 비고 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/operator/orgs/{orgId}/suspend` | 조직 정지. 본문 `reason`(운영 기록용, 200자) | 운영자 🔒 | SEC-ORG-12 |
| `POST` | `/api/operator/orgs/{orgId}/unsuspend` | 정지 해제 | 운영자 🔒 | |

- API-OPS-01 (필수): 운영자 API는 조직 식별자와 상태만 다룬다. 조직·팀의 업무 데이터, 구성원 목록, 이메일을 돌려주지 않는다(SEC-ORG-12). 운영자 행동은 모두 활동 기록에 남긴다.
- API-OPS-02 (필수): 운영자가 아닌 계정이 운영자 API를 부르면 `404 NOT_FOUND`다.

## 13. 검증

| ID | 검증 | 방법 | 수준 |
| --- | --- | --- | --- |
| API-TEST-01 | 모든 엔드포인트에 권한 선언이 있고, 이 문서의 목록과 실제 엔드포인트 목록이 같다 | 시작 검증 + 목록 비교 테스트 | 필수 |
| API-TEST-02 | 모든 `POST`·`PUT`·`PATCH`·`DELETE`가 CSRF 헤더 없이 `403 CSRF_INVALID` | 통합 테스트 | 필수 |
| API-TEST-03 | 요청 DTO에 없는 필드(`orgId`, `role`, `orgAdmin` 등)를 넣으면 `400` | 통합 테스트 | 필수 |
| API-TEST-04 | §8·§9의 모든 경로를 다른 조직·팀 식별자로 부르면 `404`이고 본문이 같다(PRD-MVP-10) | 격리 테스트([architecture/tenancy.md](tenancy.md) §11.3 데이터) | 필수 |
| API-TEST-05 | 🔒 표시 API가 인증 시각 1시간 뒤 `401 REAUTH_REQUIRED` | 통합 테스트 | 필수 |
| API-TEST-06 | 같은 `Idempotency-Key`로 두 번 보내면 자원이 하나만 생긴다 | 통합 테스트 | 필수 |
| API-TEST-07 | 오류 응답에 `code`, `message`, `traceId`(400만 `fields`) 밖의 값이 없다 | 통합 테스트 | 필수 |