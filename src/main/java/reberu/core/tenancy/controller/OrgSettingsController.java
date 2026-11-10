package reberu.core.tenancy.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.access.OrgContext;
import reberu.core.support.RequiresRecentAuth;
import reberu.core.tenancy.dto.UpdateOrgSettingsRequest;
import reberu.core.tenancy.service.OrgSettingsService;
import reberu.core.tenancy.service.OrgSettingsService.OrgOverview;
import reberu.core.tenancy.service.OrgSettingsService.OrgSettings;
import reberu.core.tenancy.service.OrgSettingsService.OrgSummary;

@RestController
@RequestMapping("/api/orgs/{orgId}")
class OrgSettingsController {

    private final OrgSettingsService orgSettings;

    OrgSettingsController(OrgSettingsService orgSettings) {
        this.orgSettings = orgSettings;
    }

    @GetMapping
    @Access(value = AccessLevel.ORG_MEMBER, guestAllowed = true)
    OrgSummary summary(OrgContext org) {
        return orgSettings.summary(org);
    }

    @GetMapping("/overview")
    @Access(AccessLevel.ORG_ADMIN)
    OrgOverview overview(OrgContext org) {
        return orgSettings.overview(org.orgId());
    }

    @GetMapping("/settings")
    @Access(AccessLevel.ORG_ADMIN)
    OrgSettings settings(OrgContext org) {
        return orgSettings.settings(org.orgId());
    }

    @PatchMapping("/settings")
    @RequiresRecentAuth
    @Access(value = AccessLevel.ORG_ADMIN, write = true)
    OrgSettings update(OrgContext org, @Valid @RequestBody UpdateOrgSettingsRequest body) {
        return orgSettings.update(org.orgId(), org.accountId(), body.toChange());
    }

    @PostMapping("/archive")
    @RequiresRecentAuth
    @Access(AccessLevel.ORG_ADMIN)
    ResponseEntity<Void> archive(OrgContext org) {
        orgSettings.archive(org.orgId(), org.accountId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/restore")
    @RequiresRecentAuth
    @Access(AccessLevel.ORG_ADMIN)
    ResponseEntity<Void> restore(OrgContext org) {
        orgSettings.restore(org.orgId(), org.accountId());
        return ResponseEntity.noContent().build();
    }

}
