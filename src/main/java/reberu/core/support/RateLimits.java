package reberu.core.support;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public interface RateLimits {

    String IP_PREFIX = "ip:";
    String ACCOUNT_PREFIX = "account:";

    void acquire(RateLimit rule, String key);

    static String ipKey(String address) {
        return IP_PREFIX + network(address);
    }

    static String accountKey(Object accountId) {
        return ACCOUNT_PREFIX + accountId;
    }

    private static String network(String address) {
        if (address == null || address.indexOf(':') < 0) {
            return address;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            if (!(parsed instanceof Inet6Address)) {
                return parsed.getHostAddress();
            }
            byte[] bytes = parsed.getAddress();
            return String.format("%x:%x:%x:%x::/64", group(bytes, 0), group(bytes, 2), group(bytes, 4), group(bytes, 6));
        }
        catch (UnknownHostException malformed) {
            return address;
        }
    }

    private static int group(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

}
