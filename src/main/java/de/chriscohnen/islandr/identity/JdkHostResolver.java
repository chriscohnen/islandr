package de.chriscohnen.islandr.identity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** The JVM's resolver. Its positive cache (30 s by default) is the one the
 *  HTTP client consults next, which keeps the window between the check and
 *  the connection small — see {@link UrlFetchPolicy}. */
@ApplicationScoped
@Default
public class JdkHostResolver implements HostResolver {
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
