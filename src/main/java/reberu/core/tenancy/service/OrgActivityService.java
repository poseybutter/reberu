package reberu.core.tenancy.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import reberu.core.account.AccountProfile;
import reberu.core.account.Accounts;
import reberu.core.audit.ActivityLogs;
import reberu.core.audit.OrgActivityRecord;
import reberu.core.audit.OrgActivitySearch;
import reberu.core.support.CursorCodec;
import reberu.core.support.CursorPage;
import reberu.core.support.ErrorCode;
import reberu.core.support.FieldReason;
import reberu.core.support.FieldViolation;
import reberu.core.support.ReberuException;

public class OrgActivityService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    private final ActivityLogs activityLogs;
    private final Accounts accounts;
    private final CursorCodec cursors;
    private final JdbcClient jdbc;

    OrgActivityService(ActivityLogs activityLogs, Accounts accounts, CursorCodec cursors, JdbcClient jdbc) {
        this.activityLogs = activityLogs;
        this.accounts = accounts;
        this.cursors = cursors;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CursorPage<ActivityView> list(UUID orgId, ActivityFilter filter, String cursor, int limit) {
        if (filter.action() != null && !ACTION.matcher(filter.action()).matches()) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST,
                    List.of(new FieldViolation("action", FieldReason.INVALID_FORMAT)));
        }
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw new ReberuException(ErrorCode.INVALID_REQUEST,
                    List.of(new FieldViolation("to", FieldReason.NOT_ALLOWED_VALUE)));
        }
        String scope = "org-activity:" + orgId;
        Instant afterAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = cursors.decode(scope, cursor);
            try {
                if (position.size() != 2) {
                    throw new IllegalArgumentException("커서 위치 개수 오류");
                }
                afterAt = Instant.parse(position.get(0));
                afterId = UUID.fromString(position.get(1));
            }
            catch (RuntimeException invalid) {
                throw new ReberuException(ErrorCode.INVALID_REQUEST);
            }
        }
        Map<UUID, String> teamNames = teamNames(orgId);
        List<UUID> teamIds;
        if (filter.teamId() == null) {
            teamIds = List.copyOf(teamNames.keySet());
        }
        else if (teamNames.containsKey(filter.teamId())) {
            teamIds = List.of(filter.teamId());
        }
        else {
            return new CursorPage<>(List.of(), null);
        }
        List<OrgActivityRecord> records = activityLogs.searchOrg(new OrgActivitySearch(orgId, filter.teamId(),
                teamIds, filter.from(), filter.to(), filter.actorId(), filter.action(), afterAt, afterId,
                limit + 1));
        List<OrgActivityRecord> page = records.size() > limit ? records.subList(0, limit) : records;
        Set<UUID> accountIds = new HashSet<>();
        for (OrgActivityRecord record : page) {
            if (record.actorId() != null) {
                accountIds.add(record.actorId());
            }
            if ("ACCOUNT".equals(record.targetType())) {
                accountIds.add(record.targetId());
            }
        }
        Map<UUID, AccountProfile> profiles = accounts.profiles(accountIds);
        List<ActivityView> items = page.stream().map(record -> new ActivityView(record.id(), record.occurredAt(),
                record.teamId(), record.teamId() == null ? null : teamNames.get(record.teamId()), record.actorId(),
                name(profiles, record.actorId()), record.action(), record.targetType(), record.targetId(),
                targetName(record, profiles, teamNames), record.beforeValue(), record.afterValue())).toList();
        if (records.size() <= limit) {
            return new CursorPage<>(items, null);
        }
        OrgActivityRecord last = page.get(limit - 1);
        return new CursorPage<>(items, cursors.encode(scope, List.of(last.occurredAt().toString(),
                last.id().toString())));
    }

    private Map<UUID, String> teamNames(UUID orgId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM team WHERE org_id = :org ORDER BY created_at, id")
                .param("org", orgId.toString())
                .query(rs -> {
                    names.put(UUID.fromString(rs.getString("id")), rs.getString("name"));
                });
        return names;
    }

    private static String targetName(OrgActivityRecord record, Map<UUID, AccountProfile> profiles,
            Map<UUID, String> teamNames) {
        return switch (record.targetType()) {
            case "ACCOUNT" -> name(profiles, record.targetId());
            case "TEAM" -> teamNames.get(record.targetId());
            default -> null;
        };
    }

    private static String name(Map<UUID, AccountProfile> profiles, UUID accountId) {
        AccountProfile profile = accountId == null ? null : profiles.get(accountId);
        return profile == null ? null : profile.displayName();
    }

    public record ActivityFilter(Instant from, Instant to, UUID actorId, String action, UUID teamId) {
    }

    public record ActivityView(UUID id, Instant occurredAt, UUID teamId, String teamName, UUID actorId,
            String actorName, String action, String targetType, UUID targetId, String targetName,
            String beforeValue, String afterValue) {
    }

}
