package de.chriscohnen.islandr.dns;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSDP/UPnP name lookup — the last resort for devices that answer no name
 * protocol at all: printers, NAS boxes, TVs, cameras, media servers. None of
 * them run an mDNS or LLMNR responder as a rule, and NetBIOS is a Windows
 * thing, so before this an entire class of hardware could only ever be
 * "computer-42".
 *
 * <p>Two steps, both unicast at the host being probed — SSDP's multicast group
 * ({@code 239.255.255.250}) would never cross a router, and a scan already
 * knows the address:
 *
 * <ol>
 *   <li>An {@code M-SEARCH} to UDP 1900. UPnP 1.1 §1.3.2 defines the unicast
 *       form: {@code HOST} names the device and {@code MX} is omitted, since
 *       there is no response-spreading to do with one recipient. The reply is
 *       plain-text HTTP-style headers; the one that matters is {@code LOCATION},
 *       a URL for the device description.</li>
 *   <li>A bounded HTTP GET of that URL, for the {@code <friendlyName>} the
 *       device publishes about itself.</li>
 * </ol>
 *
 * <p>The GET is the only place in the discovery chain that speaks TCP to a
 * scanned host beyond the port probe, so it is fenced in: the URL must be HTTP
 * on the host we asked (a redirect elsewhere is refused), the read is capped,
 * and the timeout is shared with the UDP step rather than added to it. The
 * name is extracted by regex rather than an XML parser — no entity resolution
 * means no XXE against a document written by an unauthenticated device.
 *
 * <p>A {@code friendlyName} is what the device calls itself for humans
 * ("Brother HL-L2350DW", "Kitchen Speaker"), not a hostname. That is usually a
 * better resource name than a hostname would be, and it is still only ever a
 * suggestion the admin edits before importing.
 */
public final class SsdpLookup {

    static final int SSDP_PORT = 1900;

    /** Device descriptions are small; anything larger is not one. */
    private static final int MAX_DESCRIPTION_BYTES = 64 * 1024;

    private static final Pattern LOCATION =
            Pattern.compile("^LOCATION:\\s*(\\S+)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern FRIENDLY_NAME =
            Pattern.compile("<friendlyName>\\s*(.*?)\\s*</friendlyName>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SsdpLookup() {}

    public static Optional<String> lookup(String targetIp, Duration timeout) {
        return lookup(targetIp, targetIp, SSDP_PORT, timeout);
    }

    /** Host/port-parameterized for testing against a fake local responder. */
    static Optional<String> lookup(String targetIp, String host, int port, Duration timeout) {
        Duration half = Duration.ofMillis(Math.max(1, timeout.toMillis() / 2));
        Optional<String> location = discoverLocation(host, port, half);
        if (location.isEmpty()) return Optional.empty();
        return fetchFriendlyName(location.get(), host, half);
    }

    private static Optional<String> discoverLocation(String host, int port, Duration timeout) {
        // No MX header: UPnP 1.1 §1.3.2 requires it be absent on a unicast
        // search, and a device that follows the spec ignores the request with it.
        String search = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + host + ":" + port + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "ST: ssdp:all\r\n"
                + "\r\n";
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
            byte[] out = search.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(out, out.length, InetAddress.getByName(host), port));

            byte[] buf = new byte[2048];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);

            String text = new String(response.getData(), 0, response.getLength(), StandardCharsets.US_ASCII);
            Matcher m = LOCATION.matcher(text);
            return m.find() ? Optional.of(m.group(1)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<String> fetchFriendlyName(String location, String expectedHost, Duration timeout) {
        try {
            URL url = URI.create(location).toURL();
            // The description must live on the device we asked. A LOCATION
            // pointing anywhere else is either broken or bait, and following it
            // would turn a LAN scan into an outbound request to a third party.
            if (!"http".equalsIgnoreCase(url.getProtocol())
                    || !expectedHost.equalsIgnoreCase(url.getHost())) {
                return Optional.empty();
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout((int) Math.max(1, timeout.toMillis()));
            conn.setReadTimeout((int) Math.max(1, timeout.toMillis()));
            conn.setRequestProperty("Accept", "text/xml, application/xml");
            try (InputStream in = conn.getInputStream()) {
                if (conn.getResponseCode() != 200) return Optional.empty();
                byte[] body = in.readNBytes(MAX_DESCRIPTION_BYTES);
                Matcher m = FRIENDLY_NAME.matcher(new String(body, StandardCharsets.UTF_8));
                if (!m.find()) return Optional.empty();
                return Optional.ofNullable(clean(m.group(1)));
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Device-supplied text: collapse whitespace, drop control characters, and
     *  cap the length. This becomes a suggested resource name in the admin's
     *  browser, so it must not carry anything but a name. */
    private static String clean(String raw) {
        if (raw == null) return null;
        String name = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (name.length() > 64) name = name.substring(0, 64).trim();
        return name.isBlank() ? null : name;
    }
}
