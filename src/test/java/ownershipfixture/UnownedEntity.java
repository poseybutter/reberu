package ownershipfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
public class UnownedEntity {

    @Id
    private UUID id;

}
