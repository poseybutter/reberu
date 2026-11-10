package reberu.core.tenancy.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.access.TeamContext;
import reberu.core.audit.ActivityEntry;
import reberu.core.audit.ActivityLogs;
import reberu.core.support.ErrorCode;
import reberu.core.support.ReberuException;
import reberu.core.tenancy.entity.Team;
import reberu.core.tenancy.repository.TeamRepository;

public class TeamViewService {

    public static final Duration ORG_ADMIN_VIEW_INTERVAL = Duration.ofHours(1);
    static final String ORG_ADMIN_VIEWED_TEAM = "ORG_ADMIN_VIEWED_TEAM";

    private final TeamRepository teams;
    private final ActivityLogs activityLogs;
    private final Clock clock;

    TeamViewService(TeamRepository teams, ActivityLogs activityLogs, Clock clock) {
        this.teams = teams;
        this.activityLogs = activityLogs;
        this.clock = clock;
    }

    @Transactional
    public TeamView view(TeamContext context) {
        Team team = teams.findByOrgIdAndId(context.orgId(), context.teamId())
                .orElseThrow(() -> new ReberuException(ErrorCode.NOT_FOUND));
        if (context.viewingAsOrgAdmin()) {
            Instant now = clock.instant();
            if (!activityLogs.orgRecordedSince(context.orgId(), context.accountId(), ORG_ADMIN_VIEWED_TEAM,
                    context.teamId(), now.minus(ORG_ADMIN_VIEW_INTERVAL))) {
                activityLogs.recordOrg(context.orgId(), new ActivityEntry(context.accountId(), ORG_ADMIN_VIEWED_TEAM,
                        "TEAM", context.teamId(), null, null));
            }
        }
        return new TeamView(team.id(), team.name(), team.state().name(), team.visibility().name(),
                context.role() == null ? null : context.role().name(), List.of());
    }

    public record TeamView(UUID id, String name, String state, String visibility, String myRole,
            List<String> modules) {
    }

}
