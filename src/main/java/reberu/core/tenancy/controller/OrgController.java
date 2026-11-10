package reberu.core.tenancy.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.support.IdempotentRequest;
import reberu.core.support.IdempotentRequests;
import reberu.core.support.RateLimit;
import reberu.core.support.RateLimited;
import reberu.core.tenancy.dto.CreateOrgRequest;
import reberu.core.tenancy.dto.CreateOrgResponse;
import reberu.core.tenancy.dto.DefaultJobTypeResponse;
import reberu.core.tenancy.service.DefaultJobTypes;
import reberu.core.tenancy.service.OrgCreationService;
import reberu.core.tenancy.service.OrgCreationService.CreatedOrg;

@RestController
@RequestMapping("/api")
@ConditionalOnExpression("!'${reberu.tenancy.mode:}'.trim().equalsIgnoreCase('SINGLE_ORG')")
class OrgController {

    private final OrgCreationService orgCreation;
    private final DefaultJobTypes defaultJobTypes;
    private final IdempotentRequests idempotentRequests;

    OrgController(OrgCreationService orgCreation, DefaultJobTypes defaultJobTypes,
            IdempotentRequests idempotentRequests) {
        this.orgCreation = orgCreation;
        this.defaultJobTypes = defaultJobTypes;
        this.idempotentRequests = idempotentRequests;
    }

    @PostMapping("/orgs")
    @Access(AccessLevel.ACCOUNT)
    @RateLimited(RateLimit.ORG_CREATE)
    ResponseEntity<String> create(@AuthenticationPrincipal UUID accountId,
            @RequestHeader(name = IdempotentRequest.HEADER, required = false) String key,
            @Valid @RequestBody CreateOrgRequest body, HttpServletRequest request) {
        IdempotentRequest idempotent = IdempotentRequest.of(accountId, key, request, body);
        return idempotentRequests.execute(idempotent, () -> {
            CreatedOrg created = orgCreation.create(accountId, body.toNewOrg());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new CreateOrgResponse(created.orgId(), created.teamId()));
        });
    }

    @GetMapping("/default-job-types")
    @Access(AccessLevel.ACCOUNT)
    List<DefaultJobTypeResponse> defaultJobTypes() {
        return defaultJobTypes.all().stream()
                .map(type -> new DefaultJobTypeResponse(type.key(), type.name(), type.description()))
                .toList();
    }

}
