package reberu.core.tenancy.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import reberu.core.tenancy.entity.TeamMembership;

public interface TeamMembershipRepository extends JpaRepository<TeamMembership, UUID> {
}
