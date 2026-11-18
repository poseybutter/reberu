package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import reberu.core.tenancy.entity.InviteLink;

public interface InviteLinkRepository extends Repository<InviteLink, UUID> {

    InviteLink save(InviteLink link);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from InviteLink l where l.teamId = :teamId and l.id = :linkId")
    Optional<InviteLink> findForUpdate(UUID teamId, UUID linkId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from InviteLink l where l.teamId = :teamId and l.enabled = true order by l.createdAt, l.id")
    List<InviteLink> findEnabledForUpdateByTeamId(UUID teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from InviteLink l where l.orgId = :orgId and l.enabled = true order by l.createdAt, l.id")
    List<InviteLink> findEnabledForUpdateByOrgId(UUID orgId);

}
