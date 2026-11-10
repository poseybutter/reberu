package ownershipfixture;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface LooseTeamThingRepository extends Repository<TeamThing, UUID> {

    Optional<TeamThing> findByName(String name);

    Optional<TeamThing> findByTeamIdAndName(UUID teamId, String name);

    TeamThing save(TeamThing thing);

}
