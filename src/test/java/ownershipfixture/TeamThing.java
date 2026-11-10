package ownershipfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import reberu.core.support.Owned;
import reberu.core.support.Owned.Ownership;

@Entity
@Owned(Ownership.TEAM)
public class TeamThing {

    @Id
    private UUID id;

    private UUID teamId;

    private String name;

}
