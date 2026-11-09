package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import reberu.core.tenancy.entity.Team;

public interface TeamRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByOrgIdAndId(UUID orgId, UUID id);

    boolean existsByOrgIdAndNameKey(UUID orgId, String nameKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.orgId = :orgId and t.id = :id")
    Optional<Team> findForUpdate(UUID orgId, UUID id);

}
