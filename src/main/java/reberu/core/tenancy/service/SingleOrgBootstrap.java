package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.FieldViolation;
import reberu.core.support.Uuids;
import reberu.core.tenancy.OrgBootstrap;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.TenancyCodes.JoinedVia;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;

class SingleOrgBootstrap implements OrgBootstrap, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SingleOrgBootstrap.class);

    static final UUID BOOTSTRAP_ORG_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final TenancyProperties properties;
    private final OrganizationRepository organizations;
    private final OrgMembershipRepository orgMemberships;
    private final DefaultJobTypes defaultJobTypes;
    private final NameRules nameRules;
    private final ActivityLogs activityLogs;
    private final TransactionTemplate transactions;
    private final Clock clock;

    SingleOrgBootstrap(TenancyProperties properties, OrganizationRepository organizations,
            OrgMembershipRepository orgMemberships, DefaultJobTypes defaultJobTypes, NameRules nameRules,
            ActivityLogs activityLogs, TransactionTemplate transactions, Clock clock) {
        this.properties = properties;
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.defaultJobTypes = defaultJobTypes;
        this.nameRules = nameRules;
        this.activityLogs = activityLogs;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.singleOrgMode()) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> ensureSingleOrg());
        }
        catch (DataIntegrityViolationException concurrent) {
            requireExactlyOne(organizations.countByStateNot(OrgState.DELETED));
        }
    }

    void ensureSingleOrg() {
        long existing = organizations.countByStateNot(OrgState.DELETED);
        if (existing > 0) {
            requireExactlyOne(existing);
            return;
        }
        TenancyProperties.SingleOrg config = properties.singleOrg();
        FieldViolation name = nameRules.checkName("SINGLE_ORG_NAME", config.name(), Organization.NAME_MIN,
                Organization.NAME_MAX);
        if (name != null) {
            throw new IllegalStateException("SINGLE_ORG_NAME 값 오류: " + name.reason());
        }
        Instant now = clock.instant();
        organizations.saveAndFlush(Organization.create(BOOTSTRAP_ORG_ID, config.name(), null, Visibility.PRIVATE,
                config.timeZone(), null, now));
        defaultJobTypes.copyTo(BOOTSTRAP_ORG_ID, now);
        activityLogs.recordOrg(BOOTSTRAP_ORG_ID,
                new ActivityEntry(null, "ORG_CREATED", "ORG", BOOTSTRAP_ORG_ID, null, "SINGLE_ORG"));
        log.info("SINGLE_ORG 조직 만들기: {}", BOOTSTRAP_ORG_ID);
    }

    private static void requireExactlyOne(long count) {
        if (count != 1) {
            throw new IllegalStateException("SINGLE_ORG 조직 수 오류: " + count + "개, 시작 중단");
        }
    }

    @Override
    @Transactional
    public boolean grantFirstAdmin(UUID accountId, String email) {
        if (!properties.singleOrgMode() || accountId == null || email == null
                || !email.strip().toLowerCase(Locale.ROOT).equals(properties.singleOrg().adminEmail())) {
            return false;
        }
        List<Organization> active = organizations.findAllForUpdateByStateNot(OrgState.DELETED);
        if (active.size() != 1) {
            return false;
        }
        UUID orgId = active.get(0).id();
        if (orgMemberships.countByOrgIdAndOrgAdminTrueAndState(orgId, MembershipState.ACTIVE) > 0) {
            return false;
        }
        Instant now = clock.instant();
        Optional<OrgMembership> existing = orgMemberships.findByOrgIdAndAccountId(orgId, accountId);
        if (existing.isPresent()) {
            if (!existing.get().canBecomeAdmin()) {
                return false;
            }
            existing.get().grantAdmin(now);
        } else {
            orgMemberships.save(OrgMembership.activeAdmin(Uuids.v7(), orgId, accountId, JoinedVia.BOOTSTRAP, now));
        }
        activityLogs.recordOrg(orgId,
                new ActivityEntry(accountId, "ORG_ADMIN_BOOTSTRAPPED", "ACCOUNT", accountId, null, "ORG_ADMIN"));
        return true;
    }

}
