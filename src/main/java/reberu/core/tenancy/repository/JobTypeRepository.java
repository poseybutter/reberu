package reberu.core.tenancy.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import reberu.core.tenancy.entity.JobType;

public interface JobTypeRepository extends JpaRepository<JobType, UUID> {

    Optional<JobType> findByOrgIdAndId(UUID orgId, UUID id);

}
