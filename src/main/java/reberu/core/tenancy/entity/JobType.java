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
@Owned(Ownership.ORG)
@Table(name = "job_type")
public class JobType {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    private String description;

    @Column(nullable = false)
    private boolean hidden;

    @Column(nullable = false)
    private String defaults;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected JobType() {
    }

    public static JobType create(UUID id, UUID orgId, String name, String nameKey, String description,
            String defaults, Instant at) {
        JobType jobType = new JobType();
        jobType.id = id;
        jobType.orgId = orgId;
        jobType.name = name;
        jobType.nameKey = nameKey;
        jobType.description = description;
        jobType.hidden = false;
        jobType.defaults = defaults;
        jobType.createdAt = at;
        jobType.updatedAt = at;
        return jobType;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean hidden() {
        return hidden;
    }

    public String defaults() {
        return defaults;
    }

    public UUID orgId() {
        return orgId;
    }

    public String nameKey() {
        return nameKey;
    }

    public String description() {
        return description;
    }

    public int version() {
        return version;
    }

    public void change(String name, String nameKey, String description, String defaults, Instant at) {
        this.name = name;
        this.nameKey = nameKey;
        this.description = description;
        this.defaults = defaults;
        updatedAt = at;
    }

    public void hide(Instant at) {
        if (hidden) {
            throw new IllegalStateException("이미 숨긴 직무 유형");
        }
        hidden = true;
        updatedAt = at;
    }

    public void unhide(Instant at) {
        if (!hidden) {
            throw new IllegalStateException("숨기지 않은 직무 유형");
        }
        hidden = false;
        updatedAt = at;
    }

}
