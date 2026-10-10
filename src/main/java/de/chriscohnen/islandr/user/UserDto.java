package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.identity.OidcCustomProvider;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class UserDto {

    public record Response(
            String id,
            String name,
            String nickname,
            String displayName,
            String email,
            boolean enabled,
            boolean isAdmin,
            String preferredLocale,
            int peerCount,
            // Access deadline (issue #53). Null = no expiry. accessExpired is
            // derived so the UI does not have to compare clocks itself, and so
            // "enabled but past the deadline" is legible at a glance.
            Instant validUntil,
            boolean accessExpired,
            Instant createdAt,
            // True when this account is tied to an OIDC identity — its `name`
            // is re-synced from the IdP's claim on every login (see
            // OidcLoginService), so directly editing it there wouldn't stick;
            // the frontend offers the nickname override for those instead of
            // a plain rename. A local-only account has no such override, so
            // its `name` is directly, durably editable.
            boolean ssoLinked,
            // users-identity-pill: a user can have both a password AND an SSO
            // link (set one, then last logged in via the other) — these two
            // travel separately rather than collapsing into `ssoLinked` so the
            // portal can show both pills at once instead of picking one.
            boolean hasLocalPassword,
            // Raw provider key ("microsoft" | "google" | "custom") for icon
            // selection, or null for a local-only account. `oidcProviderLabel`
            // is the human-facing text: fixed for microsoft/google, resolved
            // from OidcCustomProvider.displayName for "custom" (an admin names
            // their own generic-OIDC providers, issue #69).
            String oidcProvider,
            String oidcProviderLabel
    ) {
        public static Response from(User u) {
            String display = (u.nickname != null && !u.nickname.isBlank()) ? u.nickname : u.name;
            int peers = (int) de.chriscohnen.islandr.peer.Peer.count("userId", u.id);
            return new Response(u.id, u.name, u.nickname, display, u.email, u.enabled, u.isAdmin,
                    u.preferredLocale, peers, u.validUntil, u.isExpiredAt(Instant.now()), u.createdAt,
                    u.oidcProvider != null, u.passwordHash != null, u.oidcProvider, oidcProviderLabel(u));
        }

        private static String oidcProviderLabel(User u) {
            if (u.oidcProvider == null) return null;
            return switch (u.oidcProvider) {
                case "microsoft" -> "Microsoft";
                case "google" -> "Google";
                case "custom" -> {
                    OidcCustomProvider p = u.oidcCustomProviderId == null
                            ? null : OidcCustomProvider.findById(u.oidcCustomProviderId);
                    yield p != null ? p.displayName : u.oidcProvider;
                }
                default -> u.oidcProvider;
            };
        }
    }

    /**
     * Sets or clears a user's access deadline (issue #53). A null validUntil
     * clears it — the user then has no expiry, which is the default.
     */
    public record ValidUntilRequest(Instant validUntil) {}

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank @Email String email
    ) {}

    public record UpdateRequest(
            @NotBlank String name,
            @NotBlank @Email String email
    ) {}

    public record AdminFlagRequest(boolean isAdmin) {}

    /** Set (non-blank, min length enforced in the handler) or clear (blank) a local password. */
    public record PasswordRequest(String password) {}

    /** session-revoke-on-password-change: {@code sessionsRevoked} is how many
     *  of this user's other sessions were just ended, so the admin console
     *  can say so rather than leaving the effect invisible. Zero whenever
     *  this was a no-op (clearing an already-absent password) or the only
     *  live session was the caller's own. */
    public record PasswordChangeResponse(Response user, int sessionsRevoked) {}

    public record NicknameRequest(String nickname) {}

    public record LocaleRequest(String locale) {}

    public record EnabledRequest(boolean enabled) {}

    private UserDto() {}
}
