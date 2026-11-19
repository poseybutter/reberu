package reberu.core.tenancy.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.OrgContext;
import reberu.core.support.CursorPage;
import reberu.core.tenancy.dto.JoinRequestRequests;
import reberu.core.tenancy.service.JoinRequestReviewService;
import reberu.core.tenancy.service.JoinRequestReviewService.OrgRequestView;

@Validated
@RestController
@RequestMapping("/api/orgs/{orgId}/join-requests")
class OrgJoinRequestController {

    private final JoinRequestReviewService reviews;

    OrgJoinRequestController(JoinRequestReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    @Access(AccessLevel.ORG_ADMIN)
    CursorPage<OrgRequestView> list(OrgContext org, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + JoinRequestReviewService.DEFAULT_LIMIT) @Min(1)
            @Max(JoinRequestReviewService.MAX_LIMIT) int limit) {
        return reviews.listOrg(org.orgId(), cursor, limit);
    }

    @PostMapping("/{accountId}/approve")
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> approve(OrgContext org, @PathVariable UUID accountId,
            @RequestBody(required = false) JoinRequestRequests.Approve body) {
        reviews.approveOrg(org.orgId(), org.accountId(), accountId, body == null ? null : body.teamId(),
                body == null ? null : body.role());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{accountId}/reject")
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> reject(OrgContext org, @PathVariable UUID accountId) {
        reviews.rejectOrg(org.orgId(), org.accountId(), accountId);
        return ResponseEntity.noContent().build();
    }

}
