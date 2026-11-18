package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import reberu.core.tenancy.entity.InviteLink;

public interface InviteLinkRepository extends Repository<InviteLink, UUID> {

    InviteLink save(InviteLink link);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from InviteLink l where l.teamId = :teamId and l.id = :linkId")
    Optional<InviteLink> findForUpdate(UUID teamId, UUID linkId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update InviteLink l set l.useCount = l.useCount + 1, l.updatedAt = :at, l.version = l.version + 1
            where l.teamId = :teamId and l.id = :linkId and l.enabled = true and l.useCount < l.maxUses
              and l.expiresAt > :at
            """)
    int incrementUse(UUID teamId, UUID linkId, Instant at);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from InviteLink l where l.teamId = :teamId and l.enabled = true order by l.createdAt, l.id")
    List<InviteLink> findEnabledForUpdateByTeamId(UUID teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from InviteLink l where l.orgId = :orgId and l.enabled = true order by l.createdAt, l.id")
    List<InviteLink> findEnabledForUpdateByOrgId(UUID orgId);

}
