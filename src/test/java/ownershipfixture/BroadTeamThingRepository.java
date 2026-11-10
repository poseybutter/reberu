package ownershipfixture;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadTeamThingRepository extends JpaRepository<TeamThing, UUID> {
}
