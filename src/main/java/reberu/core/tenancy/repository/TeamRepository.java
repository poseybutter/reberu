package reberu.core.tenancy.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import reberu.core.tenancy.entity.Team;

public interface TeamRepository extends JpaRepository<Team, UUID> {
}
