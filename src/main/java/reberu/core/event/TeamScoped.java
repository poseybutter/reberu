package reberu.core.event;

import java.util.UUID;

public interface TeamScoped extends OrgScoped {

    UUID teamId();

}
