package de.chriscohnen.islandr.auth;

import java.util.Locale;

/**
 * The WebAuthn relying-party id for a request — derived from the host the
 * browser actually used, because that is the only value the specification
 * allows.
 *
 * <p>Two consequences shape everything around this feature, and neither is
 * something islandr can decide away:
 *
 * <ul>
 *   <li><b>An IP address cannot be a relying-party id.</b> A console reached as
 *       {@code https://10.77.140.1} cannot use security keys at all — not for
 *       want of a feature, but because there is no registrable domain to bind a
 *       credential to. The hub needs a name first, which is what the resolver's
 *       {@code hub.<zone>} record exists for.</li>
 *   <li><b>A credential is bound to the name it was registered under.</b> A key
 *       enrolled at {@code hub.islandr.internal} is simply not offered at
 *       {@code konsole.firma.de} — the browser does not even show it. Storing
 *       the id with the credential is what lets the console say so, instead of
 *       presenting a key that cannot work.</li>
 * </ul>
 */
public final class RelyingPartyId {

    private RelyingPartyId() {}

    /**
     * @param host the request's {@code Host} header, with or without a port
     * @return the relying-party id, or {@code null} when this host cannot carry
     *         one — an IP literal, or nothing at all
     */
    public static String of(String host) {
        if (host == null || host.isBlank()) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);

        // An IPv6 literal arrives bracketed. It is an address either way.
        if (h.startsWith("[")) return null;

        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        if (h.isEmpty()) return null;

        if (isIpv4Literal(h)) return null;
        // "localhost" is the one name the spec exempts from HTTPS, and it is a
        // real registrable name — development works, which is the point.
        return h;
    }

    /** True when a host can carry security keys at all — what the login screen
     *  needs in order not to offer a dead end. */
    public static boolean isUsable(String host) {
        return of(host) != null;
    }

    private static boolean isIpv4Literal(String h) {
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return false;
            }
            if (Integer.parseInt(p) > 255) return false;
        }
        return true;
    }
}
