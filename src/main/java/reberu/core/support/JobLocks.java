package reberu.core.support;

import java.time.Duration;

public interface JobLocks {

    boolean runExclusively(String job, Duration lease, Runnable task);

}
