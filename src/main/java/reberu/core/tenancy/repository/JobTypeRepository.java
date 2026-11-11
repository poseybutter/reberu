package reberu.core.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import reberu.core.tenancy.entity.JobType;

public interface JobTypeRepository extends JpaRepository<JobType, UUID> {

    Optional<JobType> findByOrgIdAndId(UUID orgId, UUID id);

    boolean existsByOrgIdAndNameKey(UUID orgId, String nameKey);

    long countByOrgId(UUID orgId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from JobType j where j.orgId = :orgId and j.id = :jobTypeId")
    Optional<JobType> findForUpdate(UUID orgId, UUID jobTypeId);

}
