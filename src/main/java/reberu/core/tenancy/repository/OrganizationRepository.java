package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import reberu.core.tenancy.entity.Organization;
import reberu.core.tenancy.entity.TenancyCodes.OrgState;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    long countByCreatedByAndStateNot(UUID createdBy, OrgState state);

    long countByStateNot(OrgState state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :orgId")
    Optional<Organization> findForUpdate(UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.state <> :state order by o.createdAt")
    List<Organization> findAllForUpdateByStateNot(OrgState state);

}
