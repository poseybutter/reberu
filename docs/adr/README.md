# 설계 결정 기록 (ADR)

> 버전: v0.4.0\
> 수정일: 2026-11-05

되돌리기 어렵거나 여러 문서에 영향을 주는 결정을 남긴다. 본문 설계 문서는 "무엇을"을, ADR은 "왜 그렇게 정했는가"를 맡는다.

## 1. 목록

| 번호 | 제목 | 상태 |
| --- | --- | --- |
| [ADR-0001](0001-work-tool-first.md) | 업무 툴 우선과 공간 계층 | Accepted |
| [ADR-0002](0002-core-and-modules.md) | 고정 기능과 모듈 구조 | Accepted |
| [ADR-0003](0003-multi-org.md) | 멀티 조직 구조와 배포 모드 | Accepted |
| [ADR-0004](0004-google-oidc-only.md) | Google OIDC 단일 로그인 | Accepted |
| [ADR-0005](0005-join-policy.md) | 가입 정책: 공개 범위와 가입 방법 | Accepted |
| [ADR-0006](0006-tenant-isolation.md) | 조직·팀 데이터 격리와 비노출 응답 | Accepted |
| [ADR-0007](0007-attendance-workday.md) | 출석: 근무일과 자정 경계 | Accepted |
| [ADR-0008](0008-realtime-split.md) | 실시간: 저장은 API, 휘발은 실시간 채널 | Accepted |
| [ADR-0009](0009-backend-first-mvp.md) | 백엔드 우선 단계적 MVP | Accepted |
| [ADR-0010](0010-space-movement.md) | 공간 이동: 공간 사이 선택, 공간 안 보행, 위치 비영속 | Accepted |
| [ADR-0011](0011-no-exp-for-rest.md) | 휴식에 EXP를 주지 않음 | Accepted |
| [ADR-0012](0012-chat-webhook-notifications.md) | 외부 알림: Google Chat 웹훅, 계정 종류 구분 | Accepted |
| [ADR-0013](0013-shared-db-portable-sql.md) | DB: 공유 테이블, 세 DB 호환, 조직 단위 이관 | Accepted |
| [ADR-0014](0014-module-architecture.md) | 모듈 구조: 단방향 의존, 이벤트 저장소, 시작 검증 | Accepted |
| [ADR-0015](0015-healing-only-spaces.md) | 공간에는 업무 기능을 두지 않음 | Accepted |
| [ADR-0016](0016-spring-modulith-events.md) | 이벤트 저장소와 경계 검증: Spring Modulith | Accepted |
| [ADR-0017](0017-prometheus-grafana.md) | 관측: Prometheus 지표와 Grafana 대시보드 | Accepted |
| [ADR-0018](0018-hashed-session-store.md) | 세션 저장소: 해시로 저장하는 직접 구현 | Accepted |
| [ADR-0019](0019-spa-frontend.md) | 프론트엔드: 같은 출처의 SPA, 보안 경계에서 전체 새로고침 | Accepted |

## 2. 규칙

- 파일 이름은 `네 자리 번호-짧은-영어-이름.md`다. 번호는 다시 쓰지 않는다.
- 상태는 `Accepted`(채택) 또는 `Superseded`(대체됨)다. 대체된 ADR은 지우지 않고, 상태에 대체한 ADR 번호를 적는다.
- 결정을 바꿀 때는 기존 ADR을 고치지 않고 새 ADR을 쓴다. 오타·링크 수정만 기존 ADR에서 한다.
- 결정을 반영한 설계 문서와 요구사항 ID를 "영향" 절에 적는다.

## 3. 템플릿

```markdown
# ADR-NNNN. 제목

- 상태: Accepted | Superseded by ADR-NNNN
- 영향: 바뀌는 문서와 요구사항 ID

## 맥락

무엇이 문제였고 어떤 제약이 있었는가.

## 결정

무엇을 하기로 했는가. 단정한 문장으로 쓴다.

## 검토한 대안

| 대안 | 버린 이유 |
| --- | --- |

## 결과

- 좋아지는 점
- 감수하는 점
- 후속 작업
```
