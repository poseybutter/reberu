package reberu.core.tenancy.dto;

import java.util.List;
import reberu.core.tenancy.service.WithdrawalService.OrgName;

public record WithdrawRequest(String confirmText, List<OrgName> orgNameConfirmations) {
}
