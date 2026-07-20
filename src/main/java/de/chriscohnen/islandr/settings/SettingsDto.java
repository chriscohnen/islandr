package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.validation.ValidCidr;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public final class SettingsDto {

    public record Response(
            String wgSubnet,
            String wgSubnet6,
            String wgServerPublicKey,
            String wgServerEndpoint,
            String wgClientAllowedIps,
            String wgClientDns,
            String privateKeyRetention,
            boolean gravatarEnabled,
            boolean oidcAutoProvision,
            boolean firewallDryRun,
            boolean selfServicePeerCreation,
            Integer wgMtu,
            boolean wgIncludeMtuInConf,
            int wgPersistentKeepalive,
            String nominatimUrl,
            Double hubLat,
            Double hubLon,
            String hubLocationLabel,
            boolean ironRdpEnabled,
            int activityRetentionDays,
            Instant updatedAt,
            String updatedBy,
            boolean setupComplete,
            String version,
            boolean encryptionKeyConfigured,
            boolean googleWsConfigured,
            String googleWsImpersonationEmail,
            String wgInterface,
            // "none" (dummy placeholder cert in effect) | "managed" | "referenced" — ADR-0015
            String tlsMode,
            // Parsed from the current certificate's notAfter; null when the dummy
            // placeholder is in effect (it has a 20-year validity, not a rotation concern).
            Instant tlsCertExpiresAt,
            // Domain/SAN/validity detail on the installed certificate — null in "none" mode.
            de.chriscohnen.islandr.tls.TlsService.CertInfo tlsCertInfo
    ) {
        public static Response from(Settings s, String version, boolean encryptionKeyConfigured, String wgInterface,
                                     Instant tlsCertExpiresAt, de.chriscohnen.islandr.tls.TlsService.CertInfo tlsCertInfo) {
            return new Response(
                    s.wgSubnet, s.wgSubnet6,
                    s.wgServerPublicKey, s.wgServerEndpoint,
                    s.wgClientAllowedIps, s.wgClientDns, s.privateKeyRetention,
                    s.gravatarEnabled, s.oidcAutoProvision, s.firewallDryRun,
                    s.selfServicePeerCreation, s.wgMtu, s.wgIncludeMtuInConf,
                    s.wgPersistentKeepalive,
                    s.nominatimUrl,
                    s.hubLat, s.hubLon, s.hubLocationLabel,
                    s.ironRdpEnabled,
                    s.activityRetentionDays,
                    s.updatedAt, s.updatedBy,
                    !s.wgServerPublicKey.startsWith("PLACEHOLDER"),
                    version,
                    encryptionKeyConfigured,
                    s.googleWsServiceAccountJson != null && !s.googleWsServiceAccountJson.isBlank(),
                    s.googleWsImpersonationEmail,
                    wgInterface,
                    s.tlsMode,
                    tlsCertExpiresAt,
                    tlsCertInfo);
        }
    }

    /** Managed-mode certificate upload — PEM-encoded X.509 cert + private key. */
    // Single combined PEM paste — certificate(s) and private key in either order.
    // de.chriscohnen.islandr.tls.TlsService#splitPemBundle does the splitting.
    public record TlsRequest(
            @NotBlank String pem
    ) {}

    public record GoogleWorkspaceRequest(
            String serviceAccountJson,
            String impersonationEmail
    ) {}

    public record UpdateRequest(
            @NotBlank @ValidCidr
            String wgSubnet,

            // optional — null/blank = IPv4-only deployment
            @ValidCidr
            String wgSubnet6,

            @NotBlank String wgServerPublicKey,
            @NotBlank String wgServerEndpoint,
            @NotBlank String wgClientAllowedIps,

            // optional — empty / null both mean "no DNS line in client conf"
            String wgClientDns,

            @NotBlank
            @Pattern(regexp = "^(never|plaintext|encrypted)$",
                    message = "must be one of: never, plaintext, encrypted")
            String privateKeyRetention,

            // optional — defaults to false (privacy-first; sends md5(email) to gravatar.com)
            boolean gravatarEnabled,

            // optional — defaults to true; set false to require manual user creation before OIDC login
            boolean oidcAutoProvision,

            // optional — when true, WG and nftables adapters run in mock mode at runtime
            boolean firewallDryRun,

            // optional — when false, POST /api/v1/peers/mine returns 403
            boolean selfServicePeerCreation,

            // optional — null means not set; stored from probe auto-save or manual entry
            Integer wgMtu,

            // optional — when true, MTU = <wgMtu> is written into client .conf files
            boolean wgIncludeMtuInConf,

            // global default PersistentKeepalive (seconds) for client .conf files.
            // 0 = no keepalive line; 1..65535 = interval. Defaults to 25 when omitted.
            @Min(0) @Max(65535) Integer wgPersistentKeepalive,

            // optional — base URL of a Nominatim instance; null/blank = geocoding disabled
            String nominatimUrl,

            // optional — hub location for topology map
            Double hubLat,
            Double hubLon,
            String hubLocationLabel,

            // optional — enable IronRDP browser-based RDP proxy (global toggle)
            boolean ironRdpEnabled,

            // optional — days to keep peer_daily_activity rows for the dashboard
            // heatmap (#32) before the cleanup job prunes them; null/0 keeps the
            // 180-day default.
            @Min(0) @Max(3650) Integer activityRetentionDays
    ) {}

    private SettingsDto() {}
}
