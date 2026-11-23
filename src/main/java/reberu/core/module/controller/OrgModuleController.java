package reberu.core.module.controller;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.OrgContext;
import reberu.core.module.dto.ModuleRequests;
import reberu.core.module.service.ModuleService;
import reberu.core.module.service.ModuleService.OrgImpact;
import reberu.core.module.service.ModuleService.OrgModuleView;
import reberu.core.support.RequiresRecentAuth;

@RestController
@RequestMapping("/api/orgs/{orgId}/modules")
class OrgModuleController {

    private final ModuleService modules;

    OrgModuleController(ModuleService modules) {
        this.modules = modules;
    }

    @GetMapping
    @Access(AccessLevel.ORG_ADMIN)
    List<OrgModuleView> list(OrgContext org) {
        return modules.listOrg(org.orgId());
    }

    @GetMapping("/{moduleKey}/impact")
    @Access(AccessLevel.ORG_ADMIN)
    OrgImpact impact(OrgContext org, @PathVariable String moduleKey) {
        return modules.orgImpact(org.orgId(), moduleKey);
    }

    @PutMapping("/{moduleKey}/allowed")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> allow(OrgContext org, @PathVariable String moduleKey,
            @Valid @RequestBody ModuleRequests.SetAllowed body) {
        modules.setAllowed(org.orgId(), moduleKey, body.allowed(), org.accountId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{moduleKey}/enabled")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    ResponseEntity<Void> enable(OrgContext org, @PathVariable String moduleKey,
            @Valid @RequestBody ModuleRequests.SetOrgEnabled body) {
        modules.setOrgEnabled(org.orgId(), moduleKey, body.enabled(), org.accountId());
        return ResponseEntity.noContent().build();
    }

}
