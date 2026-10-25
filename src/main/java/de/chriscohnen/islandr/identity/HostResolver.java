package de.chriscohnen.islandr.identity;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Name resolution as a seam, so {@link UrlFetchPolicy} can be tested against
 * "a name that resolves to 10.x" without a DNS server that says so.
 */
@FunctionalInterface
public interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
}
