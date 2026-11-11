package reberu.core.tenancy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldKind;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldTarget;

@Entity
@Owned(Ownership.TEAM)
@Table(name = "team_custom_field")
public class TeamCustomField {

    public static final int LABEL_MAX = 40;

    @Id
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CustomFieldTarget target;

    @Column(nullable = false)
    private String label;

    @Column(name = "label_key", nullable = false)
    private String labelKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CustomFieldKind kind;

    @Column(nullable = false)
    private boolean required;

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

    protected TeamCustomField() {
    }

    public static TeamCustomField create(UUID id, UUID teamId, CustomFieldTarget target, String label,
            String labelKey, CustomFieldKind kind, boolean required, int sortOrder, Instant at) {
        TeamCustomField field = new TeamCustomField();
        field.id = id;
        field.teamId = teamId;
        field.target = target;
        field.label = label;
        field.labelKey = labelKey;
        field.kind = kind;
        field.required = required;
        field.sortOrder = sortOrder;
        field.createdAt = at;
        field.updatedAt = at;
        return field;
    }

    public void change(String label, String labelKey, boolean required, int sortOrder, boolean hidden, Instant at) {
        this.label = label;
        this.labelKey = labelKey;
        this.required = required;
        this.sortOrder = sortOrder;
        this.hidden = hidden;
        this.updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    public CustomFieldTarget target() {
        return target;
    }

    public String label() {
        return label;
    }

    public String labelKey() {
        return labelKey;
    }

    public CustomFieldKind kind() {
        return kind;
    }

    public boolean required() {
        return required;
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
