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
            // Resource-name DNS resolver (ADR-0023).
            boolean dnsResolverEnabled,
            String dnsResolverZone,
            // Where the resolver forwards non-zone queries — independent of
            // wgClientDns, see Settings.java for why.
            String dnsResolverUpstream,
            Instant updatedAt,
            String updatedBy,
            boolean setupComplete,
            String version,
            boolean encryptionKeyConfigured,
            boolean googleWsConfigured,
            String googleWsImpersonationEmail,
            String wgInterface,
            // "none" (dummy placeholder cert in effect) | "managed" | "referenced" | "acme" — ADR-0015/0019
            String tlsMode,
            // Parsed from the current certificate's notAfter; null when the dummy
            // placeholder is in effect (it has a 20-year validity, not a rotation concern).
            Instant tlsCertExpiresAt,
            // Domain/SAN/validity detail on the installed certificate — null in "none" mode.
            de.chriscohnen.islandr.tls.TlsService.CertInfo tlsCertInfo,
            // ACME status (ADR-0019) — populated regardless of tlsMode, so the UI can
            // still show "last attempt" after a failed enable that never flipped the mode.
            String acmeDomain,
            Instant acmeLastAttemptAt,
            Instant acmeLastRenewalAt,
            String acmeLastError,
            // "http-01" (default) | "dns-01" — ADR-0020.
            String acmeChallengeType,
            // "cloudflare" | "manual" — only meaningful when acmeChallengeType is dns-01.
            // Never exposes the API token itself.
            String acmeDnsProvider,
            // Non-null while a "manual" dns-01 challenge is awaiting the admin adding
            // this TXT record and clicking "Continue".
            String acmeDnsPendingRecordName,
            String acmeDnsPendingRecordValue,
            // Pending Origin-Certificate CSR (#42) — non-null while an admin-generated
            // key+CSR is awaiting a matching signed certificate upload.
            String pendingCsrPem,
            Instant pendingCsrCreatedAt,
            // Explicit full/split tunnel setting (#33, ADR-0017, F-22).
            String tunnelMode,
            String allowedIpsMode,
            String splitSupernet,
            // Server-computed preview of the literal AllowedIPs string that will be
            // written into new client configs. Computed with includeDns=true (the
            // common case) — a peer with includeDns=false simply omits the DNS
            // host-route fix-up baked into this preview.
            String computedAllowedIpsPreview
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
                    s.dnsResolverEnabled,
                    s.dnsResolverZone,
                    s.dnsResolverUpstream,
                    s.updatedAt, s.updatedBy,
                    !s.wgServerPublicKey.startsWith("PLACEHOLDER"),
                    version,
                    encryptionKeyConfigured,
                    s.googleWsServiceAccountJson != null && !s.googleWsServiceAccountJson.isBlank(),
                    s.googleWsImpersonationEmail,
                    wgInterface,
                    s.tlsMode,
                    tlsCertExpiresAt,
                    tlsCertInfo,
                    s.acmeDomain,
                    s.acmeLastAttemptAt,
                    s.acmeLastRenewalAt,
                    s.acmeLastError,
                    s.acmeChallengeType,
                    s.acmeDnsProvider,
                    s.acmeDnsPendingRecordName,
                    s.acmeDnsPendingRecordValue,
                    s.pendingCsrPem,
                    s.pendingCsrCreatedAt,
                    s.tunnelMode,
                    s.allowedIpsMode,
                    s.splitSupernet,
                    de.chriscohnen.islandr.peer.AllowedIpsCalculator.compute(
                            s.tunnelMode, s.allowedIpsMode, s.wgClientAllowedIps,
                            s.wgSubnet, s.wgSubnet6, s.splitSupernet,
                            de.chriscohnen.islandr.acl.Site.enabledGatewayCidrs(),
                            s.effectiveClientDns(), true));
        }
    }

    /** Live, unsaved preview of the computed client {@code AllowedIPs} string —
     *  see {@code SettingsResource#allowedIpsPreview}. {@code sitesOutsideSupernetCount}
     *  is the number of enabled-gateway sites individually listed because
     *  {@code splitSupernet} doesn't cover them (0 outside SPLIT+AUTO, or when no
     *  supernet is configured — in that last case every site is individually listed,
     *  which is expected, not a gap to flag). */
    public record AllowedIpsPreview(String preview, int sitesOutsideSupernetCount) {}

    /** Managed-mode certificate upload — PEM-encoded X.509 cert + private key. */
    // Single combined PEM paste — certificate(s) and private key in either order.
    // de.chriscohnen.islandr.tls.TlsService#splitPemBundle does the splitting.
    public record TlsRequest(
            @NotBlank String pem
    ) {}

    /** Enables ACME mode (ADR-0019/0020) for the given domain. With the default
     *  http-01 challenge, this domain must already resolve (DNS) to this hub's
     *  public IP and have port 80 reachable from the internet, or validation
     *  will fail. challengeType/dnsProvider/dnsApiToken are all optional —
     *  omitted or blank means "keep whatever's already configured" (see
     *  {@code SettingsService#enableAcme}). */
    public record AcmeRequest(
            @NotBlank String domain,
            String challengeType,
            String dnsProvider,
            String dnsApiToken
    ) {}

    /** Generates a private key + CSR for the Origin Server Certificate tab (#42). */
    public record CsrRequest(
            @NotBlank String domain
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

            // optional — required only when allowedIpsMode=MANUAL (enforced in
            // SettingsService#update); ignored entirely when allowedIpsMode=AUTO
            String wgClientAllowedIps,

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
            @Min(0) @Max(3650) Integer activityRetentionDays,

            // optional — "FULL" or "SPLIT"; defaults to "SPLIT" when omitted
            // (SettingsService#update applies the default).
            @Pattern(regexp = "^(FULL|SPLIT)$", message = "must be FULL or SPLIT")
            String tunnelMode,

            // optional — "AUTO" or "MANUAL"; defaults to "MANUAL" when omitted, so
            // an upgrade never silently switches an existing free-text
            // wgClientAllowedIps value into computed mode.
            @Pattern(regexp = "^(AUTO|MANUAL)$", message = "must be AUTO or MANUAL")
            String allowedIpsMode,

            // optional — admin-declared CIDR sized to cover current and future site
            // networks. Only meaningful when tunnelMode=SPLIT and allowedIpsMode=AUTO.
            @ValidCidr
            String splitSupernet,

            // optional — opt-in for the resource-name DNS resolver (ADR-0023).
            // Persisted intent only; the resolver service itself is a later addition.
            boolean dnsResolverEnabled,

            // optional — base domain for the managed zone, e.g. "islandr.internal".
            // Blank while enabling defaults to "islandr.internal" (SettingsService).
            @Pattern(regexp = "^$|^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$",
                    message = "must be a valid domain name")
            String dnsResolverZone,

            // optional — where the resolver forwards non-zone queries. Blank →
            // DnsQueryHandler falls back to a hardcoded default (1.1.1.1, 8.8.8.8).
            // Independent of wgClientDns — see Settings.java.
            String dnsResolverUpstream
    ) {}

    private SettingsDto() {}
}
