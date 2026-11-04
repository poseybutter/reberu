# ADR-0004. Google OIDC 단일 로그인

> 버전: v0.1.0\
> 수정일: 2026-11-04

- 상태: Accepted
- 영향: [architecture/security.md](../architecture/security.md) 인증 절, [architecture/tenancy.md](../architecture/tenancy.md) 들어오기 절, [product/screens.md](../product/screens.md) A1

## 맥락

- reberu는 보안 취약점을 0에 가깝게 만드는 것이 목표다.
- 비밀번호를 직접 받으면 저장, 재설정, 유출 대응, 무차별 대입 방어가 모두 공격면이 된다.
- 쓰는 사람 대부분이 Google 계정을 가지고 있다. 단체가 Google Workspace를 쓰면 이메일 전체(`아이디@도메인`)가 곧 아이디다.
- 이메일은 바뀔 수 있고, 같은 이메일이 다른 Google 계정으로 다시 만들어질 수 있다.

## 결정

- 로그인은 **Google OpenID Connect만** 허용한다. 비밀번호, 이메일 인증 코드, 다른 소셜 로그인은 두지 않는다.
- 계정 식별자는 Google `sub`다. 이메일로 계정을 찾거나 이어 붙이지 않는다.
- 이메일은 초대와 맞춰 볼 때만 쓰고, Google이 확인한 이메일(`email_verified = true`)만 쓴다.
- 도메인 자동 가입은 이메일 도메인과 `hd` 값이 모두 소유 확인된 도메인과 같을 때만 쓴다([ADR-0005](0005-join-policy.md)).
- Google 접근 토큰과 갱신 토큰은 저장하지 않는다.

## 검토한 대안

| 대안 | 버린 이유 |
| --- | --- |
| 이메일·비밀번호 로그인 추가 | 비밀번호 저장과 복구가 공격면이 된다 |
| 여러 소셜 로그인 | 같은 사람이 여러 계정을 갖게 되고, 계정 연결 규칙이 공격면이 된다 |
| 이메일을 계정 식별자로 사용 | 이메일 재사용·변경 때 다른 사람이 계정을 이어받을 수 있다 |

## 결과

- 좋아지는 점: 비밀번호 관련 위협이 사라진다. 인증 강도(2단계 인증 등)를 Google에 맡긴다.
- 감수하는 점: Google 계정이 없는 사람은 쓸 수 없다. Google 장애 때 새 로그인이 안 된다.
- 후속 작업: state·nonce·PKCE, redirect URI 고정, 최근 인증 확인(민감한 관리 행동)을 [architecture/security.md](../architecture/security.md)에 요구사항으로 둔다.
