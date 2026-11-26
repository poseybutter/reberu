package reberu.core.tenancy.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.support.RequiresRecentAuth;
import reberu.core.tenancy.dto.SuspendOrgRequest;
import reberu.core.tenancy.service.OperatorService;

@RestController
@RequestMapping("/api/operator/orgs/{targetOrgId}")
@ConditionalOnExpression("!'${reberu.tenancy.mode:}'.trim().equalsIgnoreCase('SINGLE_ORG')")
class OperatorOrgController {

    private final OperatorService operator;

    OperatorOrgController(OperatorService operator) {
        this.operator = operator;
    }

    @PostMapping("/suspend")
    @RequiresRecentAuth
    @Access(AccessLevel.OPERATOR)
    ResponseEntity<Void> suspend(@AuthenticationPrincipal UUID accountId, @PathVariable UUID targetOrgId,
            @Valid @RequestBody SuspendOrgRequest body) {
        operator.suspend(accountId, targetOrgId, body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unsuspend")
    @RequiresRecentAuth
    @Access(AccessLevel.OPERATOR)
    ResponseEntity<Void> unsuspend(@AuthenticationPrincipal UUID accountId, @PathVariable UUID targetOrgId) {
        operator.unsuspend(accountId, targetOrgId);
        return ResponseEntity.noContent().build();
    }

}
