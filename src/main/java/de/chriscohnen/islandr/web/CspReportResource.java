package de.chriscohnen.islandr.web;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Receives the browser's Content-Security-Policy violation reports. The browser
 * sends them without a cookie, so the endpoint takes them unauthenticated;
 * that is why it reads at most a few kilobytes, writes at most a handful of log
 * lines a minute and never forwards anything. No external report address, the
 * install makes no outbound calls.
 */
@Path(ContentSecurityPolicy.REPORT_PATH)
public class CspReportResource {

    private static final Logger LOG = Logger.getLogger(CspReportResource.class);

    static final int MAX_BODY_BYTES = 4096;
    static final int MAX_LOGGED_CHARS = 1000;
    static final int MAX_LOGS_PER_MINUTE = 20;

    private static final AtomicLong windowStart = new AtomicLong();
    private static final AtomicInteger inWindow = new AtomicInteger();

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response report(InputStream body) {
        String text = read(body);
        if (allow(System.currentTimeMillis())) {
            LOG.warnf("csp violation report: %s", text.replaceAll("[\\r\\n]+", " "));
        }
        return Response.noContent().build();
    }

    /** At most {@link #MAX_LOGS_PER_MINUTE} per minute; the rest are dropped silently. */
    static boolean allow(long nowMillis) {
        long start = windowStart.get();
        if (nowMillis - start >= 60_000 && windowStart.compareAndSet(start, nowMillis)) {
            inWindow.set(0);
        }
        return inWindow.incrementAndGet() <= MAX_LOGS_PER_MINUTE;
    }

    static void resetThrottleForTest() {
        windowStart.set(0);
        inWindow.set(0);
    }

    private static String read(InputStream in) {
        try {
            byte[] buf = in.readNBytes(MAX_BODY_BYTES);
            String s = new String(buf, StandardCharsets.UTF_8);
            return s.length() > MAX_LOGGED_CHARS ? s.substring(0, MAX_LOGGED_CHARS) + "…" : s;
        } catch (IOException e) {
            return "";
        }
    }
}
