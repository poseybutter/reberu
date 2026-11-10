package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import reberu.core.tenancy.entity.Team;

public interface TeamRepository extends Repository<Team, UUID> {

    Team save(Team team);

    Team saveAndFlush(Team team);

    Optional<Team> findByOrgIdAndId(UUID orgId, UUID teamId);

    boolean existsByOrgIdAndNameKey(UUID orgId, String nameKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.orgId = :orgId and t.id = :teamId")
    Optional<Team> findForUpdate(UUID orgId, UUID teamId);

}
