package reberu.core.tenancy.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.OrgContext;
import reberu.core.support.IdempotentRequest;
import reberu.core.support.IdempotentRequests;
import reberu.core.tenancy.dto.JobTypeRequests;
import reberu.core.tenancy.service.JobTypeService;
import reberu.core.tenancy.service.JobTypeService.JobTypeView;

@RestController
@RequestMapping("/api/orgs/{orgId}/job-types")
class JobTypeController {

    private final JobTypeService jobTypes;
    private final IdempotentRequests idempotentRequests;

    JobTypeController(JobTypeService jobTypes, IdempotentRequests idempotentRequests) {
        this.jobTypes = jobTypes;
        this.idempotentRequests = idempotentRequests;
    }

    @GetMapping
    @Access(AccessLevel.ORG_ADMIN)
    List<JobTypeView> list(OrgContext org, @RequestParam(defaultValue = "false") boolean includeHidden) {
        return jobTypes.list(org.orgId(), includeHidden);
    }

    @PostMapping
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<String> create(OrgContext org,
            @RequestHeader(name = IdempotentRequest.HEADER, required = false) String key,
            @Valid @RequestBody JobTypeRequests.Create body, HttpServletRequest request) {
        IdempotentRequest idempotent = IdempotentRequest.of(org.accountId(), key, request, body);
        return idempotentRequests.execute(idempotent, () -> ResponseEntity.status(HttpStatus.CREATED)
                .body(new JobTypeRequests.Created(jobTypes.create(org.orgId(), org.accountId(),
                        body.toNewJobType()))));
    }

    @PatchMapping("/{jobTypeId}")
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    JobTypeView update(OrgContext org, @PathVariable UUID jobTypeId,
            @Valid @RequestBody JobTypeRequests.Update body) {
        return jobTypes.update(org.orgId(), jobTypeId, org.accountId(), body.toChange());
    }

    @PostMapping("/{jobTypeId}/hide")
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> hide(OrgContext org, @PathVariable UUID jobTypeId) {
        jobTypes.hide(org.orgId(), jobTypeId, org.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobTypeId}/unhide")
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> unhide(OrgContext org, @PathVariable UUID jobTypeId) {
        jobTypes.unhide(org.orgId(), jobTypeId, org.accountId());
        return ResponseEntity.noContent().build();
    }

}
