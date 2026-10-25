package de.chriscohnen.islandr.identity;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-scope replacement for {@link JdkHostResolver}, the companion of
 * {@link FakeHttpFetcher}: no test should depend on real DNS. Every name
 * resolves to one public address unless a test maps it elsewhere.
 */
@Mock
@ApplicationScoped
public class FakeHostResolver implements HostResolver {

    public static final String PUBLIC = "142.250.180.1";

    /** Static for the same reason as FakeHttpFetcher's stubs: the bean a test
     *  injects and the one production code gets are not guaranteed to be the
     *  same instance under @Mock. */
    private static final Map<String, String> TABLE = new ConcurrentHashMap<>();

    public void map(String host, String ip) { TABLE.put(host, ip); }

    public void reset() { TABLE.clear(); }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return new InetAddress[] { InetAddress.getByName(TABLE.getOrDefault(host, PUBLIC)) };
    }
}
