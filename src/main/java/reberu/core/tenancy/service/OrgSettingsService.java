package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.access.OrgContext;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.entity.OrgMembership;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.Organization.Settings;
import reberu.core.tenancy.entity.TeamMembership;
import reberu.core.tenancy.entity.TenancyCodes.JoinRequestMode;
import reberu.core.tenancy.entity.TenancyCodes.MembershipKind;
import reberu.core.tenancy.entity.TenancyCodes.MembershipState;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;
import reberu.core.tenancy.repository.OrgMembershipRepository;
import reberu.core.tenancy.repository.OrganizationRepository;
import reberu.core.tenancy.repository.TeamMembershipRepository;

public class OrgSettingsService {

    public static final int GUEST_DAYS_MAX = 90;
    public static final int INVITATION_TTL_DAYS_MAX = 30;
    public static final int RECENT_ACTIVITY_LIMIT = 5;

    private static final List<Field> FIELDS = List.of(
            new Field("name", Settings::name, false),
            new Field("tagline", Settings::tagline, false),
            new Field("timeZone", Settings::timeZone, true),
            new Field("visibility", Settings::visibility, true),
            new Field("joinLinkEnabled", Settings::joinLinkEnabled, true),
            new Field("joinRequestMode", Settings::joinRequestMode, true),
            new Field("memberAdmissionReview", Settings::memberAdmissionReview, true),
            new Field("guestApprovalRequired", Settings::guestApprovalRequired, true),
            new Field("guestDefaultDays", Settings::guestDefaultDays, true),
            new Field("guestMaxDays", Settings::guestMaxDays, true),
            new Field("guestOrgspaceAllowed", Settings::guestOrgspaceAllowed, true),
            new Field("invitationTtlDays", Settings::invitationTtlDays, true));

    private final OrganizationRepository organizations;
    private final OrgMembershipRepository orgMemberships;
    private final TeamMembershipRepository teamMemberships;
    private final NameRules nameRules;
    private final ActivityLogs activityLogs;
    private final JdbcClient jdbc;
    private final Clock clock;

    OrgSettingsService(OrganizationRepository organizations, OrgMembershipRepository orgMemberships,
            TeamMembershipRepository teamMemberships, NameRules nameRules, ActivityLogs activityLogs, JdbcClient jdbc,
            Clock clock) {
        this.organizations = organizations;
        this.orgMemberships = orgMemberships;
        this.teamMemberships = teamMemberships;
        this.nameRules = nameRules;
        this.activityLogs = activityLogs;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrgSummary summary(OrgContext context) {
        Organization organization = find(context.orgId());
        return new OrgSummary(organization.id(), organization.name(), organization.timeZone(), organization.state(),
                context.guest() ? MembershipKind.GUEST : MembershipKind.MEMBER, context.orgAdmin());
    }

    @Transactional(readOnly = true)
    public OrgOverview overview(UUID orgId) {
        List<TeamCount> teams = jdbc.sql("""
                        SELECT t.id, t.name, t.state,
                               (SELECT COUNT(*) FROM team_membership m
                                WHERE m.team_id = t.id AND m.state = 'ACTIVE') AS member_count
                        FROM team t
                        WHERE t.org_id = :org
                        ORDER BY t.created_at, t.id
                        """)
                .param("org", orgId.toString())
                .query((rs, rowNum) -> new TeamCount(UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getString("state"), rs.getLong("member_count")))
                .list();
        List<RecentActivity> recent = activityLogs.recentOrg(orgId, RECENT_ACTIVITY_LIMIT).stream()
                .map(record -> new RecentActivity(record.occurredAt(), record.actorId(), record.action(),
                        record.targetType(), record.targetId()))
                .toList();
        long joinRequests = orgMemberships.countByOrgIdAndState(orgId, MembershipState.REQUESTED);
        return new OrgOverview(teams.size(), teams, joinRequests, 0, recent);
    }

    @Transactional(readOnly = true)
    public OrgSettings settings(UUID orgId) {
        return OrgSettings.of(find(orgId));
    }

    @Transactional
    public OrgSettings update(UUID orgId, UUID actorId, SettingsChange change) {
        Organization organization = organizations.findForUpdate(orgId).orElseThrow(OrgSettingsService::notFound);
        if (change.version() == null || change.version() != organization.version()) {
            throw new ReberuException(ErrorCode.VERSION_CONFLICT);
        }
        if (organization.state() != OrgState.ACTIVE) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        Settings before = organization.settings();
        Settings after = merge(before, change);
        if (after.equals(before)) {
            return OrgSettings.of(organization);
        }
        organization.applySettings(after, clock.instant());
        organizations.flush();
        List<String> changed = changedFields(before, after);
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_SETTINGS_CHANGED", "ORG", orgId,
                describe(before, changed), describe(after, changed)));
        return OrgSettings.of(organization);
    }

    @Transactional
    public void archive(UUID orgId, UUID actorId) {
        Organization organization = organizations.findForUpdate(orgId).orElseThrow(OrgSettingsService::notFound);
        if (organization.state() == OrgState.ARCHIVED) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        if (organization.state() != OrgState.ACTIVE) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        Instant now = clock.instant();
        organization.archive(now);
        for (OrgMembership request : orgMemberships.findByOrgIdAndStateOrderByCreatedAtAscIdAsc(orgId,
                MembershipState.REQUESTED)) {
            request.reject(now);
            activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_MEMBER_REJECTED", "ACCOUNT",
                    request.accountId(), "REQUESTED", "REJECTED"));
        }
        for (TeamMembership request : teamMemberships.findByOrgIdAndState(orgId, MembershipState.REQUESTED)) {
            request.reject(now);
            activityLogs.recordTeam(request.teamId(), new ActivityEntry(actorId, "TEAM_MEMBER_REJECTED", "ACCOUNT",
                    request.accountId(), "REQUESTED", "REJECTED"));
        }
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_ARCHIVED", "ORG", orgId, "ACTIVE", "ARCHIVED"));
    }

    @Transactional
    public void restore(UUID orgId, UUID actorId) {
        Organization organization = organizations.findForUpdate(orgId).orElseThrow(OrgSettingsService::notFound);
        if (organization.ownerless()) {
            throw notFound();
        }
        if (organization.state() == OrgState.ACTIVE) {
            throw new ReberuException(ErrorCode.ALREADY_PROCESSED);
        }
        if (organization.state() != OrgState.ARCHIVED) {
            throw new ReberuException(ErrorCode.READ_ONLY);
        }
        organization.restore(clock.instant());
        activityLogs.recordOrg(orgId, new ActivityEntry(actorId, "ORG_RESTORED", "ORG", orgId, "ARCHIVED", "ACTIVE"));
    }

    private Settings merge(Settings current, SettingsChange change) {
        List<FieldViolation> violations = new ArrayList<>();
        String name = current.name();
        if (change.name() != null) {
            add(violations, nameRules.checkName("name", change.name(), Organization.NAME_MIN, Organization.NAME_MAX));
            name = change.name().strip();
        }
        String tagline = current.tagline();
        if (change.tagline() != null) {
            add(violations, nameRules.checkOptionalText("tagline", change.tagline(), Organization.TAGLINE_MAX));
            tagline = change.tagline().isBlank() ? null : change.tagline().strip();
        }
        String timeZone = current.timeZone();
        if (change.timeZone() != null) {
            if (!TenancyProperties.validTimeZone(change.timeZone())) {
                violations.add(new FieldViolation("timeZone", FieldReason.NOT_ALLOWED_VALUE));
            }
            timeZone = change.timeZone();
        }
        Visibility visibility = valueOr(change.visibility(), current.visibility());
        JoinRequestMode joinRequestMode = valueOr(change.joinRequestMode(), current.joinRequestMode());
        if (visibility == Visibility.PRIVATE && joinRequestMode != JoinRequestMode.OFF) {
            if (change.joinRequestMode() != null) {
                violations.add(new FieldViolation("joinRequestMode", FieldReason.NOT_ALLOWED_VALUE));
            }
            joinRequestMode = JoinRequestMode.OFF;
        }
        int guestMaxDays = valueOr(change.guestMaxDays(), current.guestMaxDays());
        if (guestMaxDays < 1 || guestMaxDays > GUEST_DAYS_MAX) {
            violations.add(new FieldViolation("guestMaxDays", FieldReason.NOT_ALLOWED_VALUE));
        }
        int guestDefaultDays = valueOr(change.guestDefaultDays(), current.guestDefaultDays());
        if (guestDefaultDays < 1 || guestDefaultDays > guestMaxDays) {
            violations.add(new FieldViolation("guestDefaultDays", FieldReason.NOT_ALLOWED_VALUE));
        }
        int invitationTtlDays = valueOr(change.invitationTtlDays(), current.invitationTtlDays());
        if (invitationTtlDays < 1 || invitationTtlDays > INVITATION_TTL_DAYS_MAX) {
            violations.add(new FieldViolation("invitationTtlDays", FieldReason.NOT_ALLOWED_VALUE));
        }
        if (!violations.isEmpty()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST, violations);
        }
        return new Settings(name, tagline, timeZone, visibility,
                valueOr(change.joinLinkEnabled(), current.joinLinkEnabled()), joinRequestMode,
                valueOr(change.memberAdmissionReview(), current.memberAdmissionReview()),
                valueOr(change.guestApprovalRequired(), current.guestApprovalRequired()), guestDefaultDays,
                guestMaxDays, valueOr(change.guestOrgspaceAllowed(), current.guestOrgspaceAllowed()),
                invitationTtlDays);
    }

    private static List<String> changedFields(Settings before, Settings after) {
        return FIELDS.stream()
                .filter(field -> !Objects.equals(field.value().apply(before), field.value().apply(after)))
                .map(Field::name)
                .toList();
    }

    private static String describe(Settings settings, List<String> changed) {
        List<String> parts = new ArrayList<>();
        for (Field field : FIELDS) {
            if (changed.contains(field.name())) {
                parts.add(field.logged() ? field.name() + "=" + field.value().apply(settings) : field.name());
            }
        }
        return String.join(";", parts);
    }

    private Organization find(UUID orgId) {
        return organizations.findById(orgId).orElseThrow(OrgSettingsService::notFound);
    }

    private static <T> T valueOr(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static void add(List<FieldViolation> violations, FieldViolation violation) {
        if (violation != null) {
            violations.add(violation);
        }
    }

    private static ReberuException notFound() {
        return new ReberuException(ErrorCode.NOT_FOUND);
    }

    private record Field(String name, Function<Settings, Object> value, boolean logged) {
    }

    public record SettingsChange(Integer version, String name, String tagline, String timeZone, Visibility visibility,
            Boolean joinLinkEnabled, JoinRequestMode joinRequestMode, Boolean memberAdmissionReview,
            Boolean guestApprovalRequired, Integer guestDefaultDays, Integer guestMaxDays,
            Boolean guestOrgspaceAllowed, Integer invitationTtlDays) {
    }

    public record OrgSummary(UUID id, String name, String timeZone, OrgState state, MembershipKind myKind,
            boolean orgAdmin) {
    }

    public record OrgSettings(String name, String tagline, String timeZone, Visibility visibility,
            boolean joinLinkEnabled, JoinRequestMode joinRequestMode, boolean memberAdmissionReview,
            boolean guestApprovalRequired, int guestDefaultDays, int guestMaxDays, boolean guestOrgspaceAllowed,
            int invitationTtlDays, OrgState state, int version) {

        static OrgSettings of(Organization organization) {
            Settings settings = organization.settings();
            return new OrgSettings(settings.name(), settings.tagline(), settings.timeZone(), settings.visibility(),
                    settings.joinLinkEnabled(), settings.joinRequestMode(), settings.memberAdmissionReview(),
                    settings.guestApprovalRequired(), settings.guestDefaultDays(), settings.guestMaxDays(),
                    settings.guestOrgspaceAllowed(), settings.invitationTtlDays(), organization.state(),
                    organization.version());
        }

    }

    public record TeamCount(UUID id, String name, String state, long memberCount) {
    }

    public record RecentActivity(Instant occurredAt, UUID actorId, String action, String targetType, UUID targetId) {
    }

    public record OrgOverview(int teamCount, List<TeamCount> teams, long pendingJoinRequests,
            long pendingInvitations, List<RecentActivity> recentActivity) {
    }

}
