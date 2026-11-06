package reberu.core.support;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuids() {
    }

    public static UUID v7() {
        return v7(Clock.systemUTC().millis());
    }

    static UUID v7(long epochMillis) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16
                | 0x7000L
                | ((random[0] & 0x0FL) << 8)
                | (random[1] & 0xFFL);
        long lsb = 0x8000_0000_0000_0000L | ((random[2] & 0x3FL) << 56);
        for (int i = 3; i < 10; i++) {
            lsb |= (random[i] & 0xFFL) << (8 * (9 - i));
        }
        return new UUID(msb, lsb);
    }

}
