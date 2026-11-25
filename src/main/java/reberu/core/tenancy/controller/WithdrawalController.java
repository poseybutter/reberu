package reberu.core.tenancy.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reberu.core.access.Access;
import reberu.core.access.AccessLevel;
import reberu.core.support.RequiresRecentAuth;
import reberu.core.tenancy.dto.WithdrawRequest;
import reberu.core.tenancy.service.WithdrawalService;
import reberu.core.tenancy.service.WithdrawalService.WithdrawalCheck;

@RestController
@RequestMapping("/api/me")
class WithdrawalController {

    private final WithdrawalService withdrawals;

    WithdrawalController(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    @GetMapping("/withdrawal-check")
    @Access(AccessLevel.ACCOUNT)
    WithdrawalCheck check(@AuthenticationPrincipal UUID accountId) {
        return withdrawals.check(accountId);
    }

    @PostMapping("/withdraw")
    @RequiresRecentAuth
    @Access(AccessLevel.ACCOUNT)
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal UUID accountId, @RequestBody WithdrawRequest body,
            HttpServletRequest request) {
        withdrawals.withdraw(accountId, body.confirmText(), body.orgNameConfirmations());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

}
