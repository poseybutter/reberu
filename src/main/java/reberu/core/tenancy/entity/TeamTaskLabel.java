package reberu.core.tenancy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;

@Entity
@Owned(Ownership.TEAM)
@Table(name = "team_task_label")
public class TeamTaskLabel {

    public static final int NAME_MAX = 30;

    @Id
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean hidden;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected TeamTaskLabel() {
    }

    public static TeamTaskLabel create(UUID id, UUID teamId, String name, String nameKey, int sortOrder, Instant at) {
        TeamTaskLabel label = new TeamTaskLabel();
        label.id = id;
        label.teamId = teamId;
        label.name = name;
        label.nameKey = nameKey;
        label.sortOrder = sortOrder;
        label.createdAt = at;
        label.updatedAt = at;
        return label;
    }

    public void change(String name, String nameKey, int sortOrder, boolean hidden, Instant at) {
        this.name = name;
        this.nameKey = nameKey;
        this.sortOrder = sortOrder;
        this.hidden = hidden;
        this.updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String nameKey() {
        return nameKey;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public boolean hidden() {
        return hidden;
    }

    public int version() {
        return version;
    }

}
