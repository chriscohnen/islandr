package de.chriscohnen.islandr.user;

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
            boolean ssoLinked
    ) {
        public static Response from(User u) {
            String display = (u.nickname != null && !u.nickname.isBlank()) ? u.nickname : u.name;
            int peers = (int) de.chriscohnen.islandr.peer.Peer.count("userId", u.id);
            return new Response(u.id, u.name, u.nickname, display, u.email, u.enabled, u.isAdmin,
                    u.preferredLocale, peers, u.validUntil, u.isExpiredAt(Instant.now()), u.createdAt,
                    u.oidcProvider != null);
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

    public record NicknameRequest(String nickname) {}

    public record LocaleRequest(String locale) {}

    public record EnabledRequest(boolean enabled) {}

    private UserDto() {}
}
