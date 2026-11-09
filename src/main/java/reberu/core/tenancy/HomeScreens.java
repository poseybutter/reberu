package reberu.core.tenancy;

import java.util.UUID;

public interface HomeScreens {

    HomeNext next(UUID accountId);

    record HomeNext(Screen screen, UUID teamId) {

        public enum Screen {
            TEAM_HOME,
            TEAM_PICKER,
            JOIN_PENDING,
            START
        }

    }

}
