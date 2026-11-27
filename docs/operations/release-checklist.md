# 릴리스 점검

> 버전: v0.1.0\
> 수정일: 2026-11-27

단계(MVP 단계, [product/overview.md](../product/overview.md) §6)를 마칠 때 확인한 것을 남긴다. 완료 기준이 어느 테스트로 확인되는지를 여기서 찾는다. 보안 테스트의 정의는 [architecture/security.md](../architecture/security.md) §15를 따른다.

## 1. v0.1

### 1.1 보안 테스트 연결표

[architecture/security.md](../architecture/security.md) §15.1의 v0.1 보안 테스트 ID와 그 ID를 이름에 적은 테스트 클래스다. 이 표는 `SecurityTestIdsTest`가 실제 테스트와 대조한다. 테스트를 더하거나 옮기면 표도 함께 고친다.

| 테스트 ID | 테스트 클래스 |
| --- | --- |
| `T-AUTH-STATE` | `GoogleLoginTest` |
| `T-AUTH-NONCE` | `GoogleLoginTest` |
| `T-AUTH-TOKEN` | `GoogleLoginTest` |
| `T-AUTH-SUB` | `GoogleLoginTest` |
| `T-AUTH-REDIRECT` | `GoogleLoginTest` |
| `T-AUTH-CONSENT` | `GoogleLoginTest` |
| `T-SESS-ROTATE` | `AuthSessionRepositoryTest`, `GoogleLoginTest`, `ReauthTest`, `SessionCookieTest` |
| `T-SESS-COOKIE` | `SessionCookieTest` |
| `T-SESS-REVOKE` | `SessionRevokeTest` |
| `T-SESS-REAUTH` | `ReauthTest` |
| `T-SESS-STORE` | `AuthSessionRepositoryTest` |
| `T-CSRF` | `CsrfTest`, `GoogleLoginTest` |
| `T-ISO-ORG` | `GuestTest`, `IsolationTest`, `JoinRequestReviewTest` |
| `T-ISO-TEAM` | `IsolationTest`, `JoinRequestReviewTest` |
| `T-AUTHZ-DECLARED` | `AccessDeclarationTest` |
| `T-AUTHZ-MATRIX` | 없음 |
| `T-AUTHZ-MASS` | `JobTypeTest`, `OrgSettingsTest` |
| `T-AUTHZ-LASTADMIN` | `LeaveTest`, `MemberTest`, `TeamMemberTest` |
| `T-GUEST-SCOPE` | `GuestTest` |
| `T-TOKEN-*` | `InviteLinkRedeemTest`, `InviteLinkTest` |
| `T-INV-EMAIL` | `MyInvitationTest` |
| `T-ORG-HIDDEN` | `JoinRequestTest` |
| `T-ORG-LIMIT` | `OrgCreationTest` |
| `T-ORG-OPEN` | `JoinRequestTest` |
| `T-HDR` | `SecurityHeadersTest` |
| `T-RATE` | `RateLimitDeclarationTest`, `RateLimitTest`, `SlidingWindowRateLimitsTest` |
| `T-LOG-MASK` | `LogMaskingTest` |
| `T-AUDIT-RECORD` | `ActivityLogTest` |
| `T-AUDIT-IMMUTABLE` | `ActivityLogImmutabilityTest` |
| `T-PRIV-DELETE` | `WithdrawalTest` |
| `T-MODULE-OFF` | `ModuleAccessTest` |

- `T-TOKEN-*`는 `T-TOKEN`으로 시작하는 이름을 모두 센다.
- `없음`은 아직 테스트가 없는 ID다. 테스트를 더하면 `SecurityTestIdsTest`의 `NOT_YET`에서도 지운다.
- 테스트 이름에는 security.md §15.1에 있는 ID만 쓴다. 표에 없는 ID를 쓰면 `SecurityTestIdsTest`가 실패한다.
