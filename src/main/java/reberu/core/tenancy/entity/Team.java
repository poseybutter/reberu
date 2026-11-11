package reberu.core.tenancy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;
import reberu.core.tenancy.entity.TenancyCodes.TeamJoinMode;
import reberu.core.tenancy.entity.TenancyCodes.TeamState;
import reberu.core.tenancy.entity.TenancyCodes.Visibility;

@Entity
@Owned(Ownership.TEAM)
@Table(name = "team")
public class Team {

    public static final int NAME_MAX = 40;
    public static final int NOTICE_TITLES_MAX = 400;

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "join_mode", nullable = false)
    private TeamJoinMode joinMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TeamState state;

    @Column(name = "job_type_id", updatable = false)
    private UUID jobTypeId;

    @Column(name = "report_week_start", nullable = false)
    private int reportWeekStart;

    @Column(name = "report_lock_weekday")
    private Integer reportLockWeekday;

    @Column(name = "report_lock_time")
    private LocalTime reportLockTime;

    @Column(name = "report_notice_titles", nullable = false)
    private String reportNoticeTitles;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected Team() {
    }

    public static Team create(UUID id, UUID orgId, String name, String nameKey, UUID jobTypeId,
            int reportWeekStart, String reportNoticeTitles, Instant at) {
        Team team = new Team();
        team.id = id;
        team.orgId = orgId;
        team.name = name;
        team.nameKey = nameKey;
        team.visibility = Visibility.PRIVATE;
        team.joinMode = TeamJoinMode.APPROVAL;
        team.state = TeamState.ACTIVE;
        team.jobTypeId = jobTypeId;
        team.reportWeekStart = reportWeekStart;
        team.reportNoticeTitles = reportNoticeTitles;
        team.createdAt = at;
        team.updatedAt = at;
        return team;
    }

    public void makePublic(Instant at) {
        visibility = Visibility.PUBLIC;
        updatedAt = at;
    }

    public void applySettings(String name, String nameKey, Visibility visibility, TeamJoinMode joinMode,
            int reportWeekStart, String reportNoticeTitles, Instant at) {
        this.name = name;
        this.nameKey = nameKey;
        this.visibility = visibility;
        this.joinMode = joinMode;
        this.reportWeekStart = reportWeekStart;
        this.reportNoticeTitles = reportNoticeTitles;
        this.updatedAt = at;
    }

    public void archive(Instant at) {
        if (state != TeamState.ACTIVE) {
            throw new IllegalStateException("보관할 수 없는 팀 상태: " + state);
        }
        state = TeamState.ARCHIVED;
        updatedAt = at;
    }

    public void restore(Instant at) {
        if (state != TeamState.ARCHIVED) {
            throw new IllegalStateException("복원할 수 없는 팀 상태: " + state);
        }
        state = TeamState.ACTIVE;
        updatedAt = at;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID orgId() {
        return orgId;
    }

    public String name() {
        return name;
    }

    public String nameKey() {
        return nameKey;
    }

    public Visibility visibility() {
        return visibility;
    }

    public TeamState state() {
        return state;
    }

    public UUID jobTypeId() {
        return jobTypeId;
    }

    public int reportWeekStart() {
        return reportWeekStart;
    }

    public String reportNoticeTitles() {
        return reportNoticeTitles;
    }

    public TeamJoinMode joinMode() {
        return joinMode;
    }

    public int version() {
        return version;
    }

}
