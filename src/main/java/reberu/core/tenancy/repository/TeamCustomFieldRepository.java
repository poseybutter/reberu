package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import reberu.core.tenancy.entity.TeamCustomField;
import reberu.core.tenancy.entity.TenancyCodes.CustomFieldTarget;

public interface TeamCustomFieldRepository extends Repository<TeamCustomField, UUID> {

    TeamCustomField saveAndFlush(TeamCustomField field);

    void flush();

    @Query("""
            select f from TeamCustomField f where f.teamId = :teamId
            order by f.target, f.sortOrder, f.createdAt, f.id
            """)
    List<TeamCustomField> findByTeamId(UUID teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from TeamCustomField f where f.teamId = :teamId and f.id = :fieldId")
    Optional<TeamCustomField> findForUpdate(UUID teamId, UUID fieldId);

    boolean existsByTeamIdAndTargetAndLabelKey(UUID teamId, CustomFieldTarget target, String labelKey);

    long countByTeamIdAndTarget(UUID teamId, CustomFieldTarget target);

    @Query("""
            select coalesce(max(f.sortOrder), -1) from TeamCustomField f
            where f.teamId = :teamId and f.target = :target
            """)
    int findMaxSortOrder(UUID teamId, CustomFieldTarget target);

}
