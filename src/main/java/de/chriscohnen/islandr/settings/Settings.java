package de.chriscohnen.islandr.settings;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Singleton — exactly one row, {@code id = 1}, enforced by the DB CHECK.
 * Loaded by {@link SettingsService}, never via direct queries from outside that class.
 * See <a href="../../../../../../../docs/adr/0008-runtime-settings-in-db.md">ADR-0008</a>.
 */
@Entity
@Table(name = "settings")
public class Settings extends PanacheEntityBase {

    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    public int id = SINGLETON_ID;

    @Column(name = "wg_subnet", nullable = false, length = 50)
    public String wgSubnet;

    /** Optional IPv6 ULA subnet for dual-stack WireGuard peers (e.g. {@code fd11::/64}).
     *  null = IPv4-only deployment. */
    @Column(name = "wg_subnet6", length = 50)
    public String wgSubnet6;

    @Column(name = "wg_server_public_key", nullable = false, length = 44)
    public String wgServerPublicKey;

    @Column(name = "wg_server_endpoint", nullable = false, length = 255)
    public String wgServerEndpoint;

    @Column(name = "wg_client_allowed_ips", nullable = false, columnDefinition = "TEXT")
    public String wgClientAllowedIps;

    @Column(name = "wg_client_dns", length = 255)
    public String wgClientDns;

    @Column(name = "private_key_retention", nullable = false, length = 20)
    public String privateKeyRetention;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false, length = 255)
    public String updatedBy;

    @Column(name = "gravatar_enabled", nullable = false)
    public boolean gravatarEnabled = false;

    @Column(name = "oidc_auto_provision", columnDefinition = "INTEGER")
    public boolean oidcAutoProvision = true;

    // When true: WG and nftables adapters run in mock mode regardless of the
    // ISLANDR_WG_MODE / ISLANDR_NFT_MODE env vars. Lets admins pause firewall
    // writes at runtime without restarting the service.
    @Column(name = "firewall_dry_run", columnDefinition = "INTEGER")
    public boolean firewallDryRun = true;

    @Column(name = "self_service_peer_creation", columnDefinition = "INTEGER")
    public boolean selfServicePeerCreation = true;

    @Column(name = "wg_mtu")
    public Integer wgMtu;

    @Column(name = "wg_include_mtu_in_conf", columnDefinition = "INTEGER")
    public boolean wgIncludeMtuInConf = false;

    // Global default PersistentKeepalive for client .conf files, in seconds.
    // The value is the switch: 0 = no keepalive line, 1..65535 = interval.
    // Default 25 preserves the value hardcoded before issue #28. A per-peer
    // override lives on Peer.persistentKeepalive.
    @Column(name = "wg_persistent_keepalive", nullable = false)
    public int wgPersistentKeepalive = 25;

    // Built-in TLS termination (ADR-0015). "none" (default) = serve HTTPS with the
    // baked-in dummy placeholder cert (src/main/resources/tls-dummy) until an admin
    // uploads real material. "managed" = tlsCertPem/tlsKeyPem hold the admin-supplied
    // certificate; the key is encrypted at rest when EncryptionService is configured
    // (same guarantee as the Google Workspace service-account secret). "referenced" =
    // tlsCertPath/tlsKeyPath point at a file pair islandr does not own or copy (an
    // operator's own ACME client, a CDN's origin-cert tooling).
    @Column(name = "tls_mode", nullable = false, length = 20)
    public String tlsMode = "none";

    @Column(name = "tls_cert_pem", columnDefinition = "TEXT")
    public String tlsCertPem;

    @Column(name = "tls_key_pem", columnDefinition = "TEXT")
    public String tlsKeyPem;

    @Column(name = "tls_cert_path", length = 512)
    public String tlsCertPath;

    @Column(name = "tls_key_path", length = 512)
    public String tlsKeyPath;

    // Origin Server Certificate CSR generation (#42): an admin can have islandr
    // generate a private key + PKCS#10 CSR instead of bringing their own, then
    // paste the CA-signed certificate back once it arrives — no need to paste
    // the private key again, islandr already has it. Cleared on a successful
    // certificate upload, on switching to ACME, or on a reset to the dummy cert.
    @Column(name = "pending_csr_pem", columnDefinition = "TEXT")
    public String pendingCsrPem;

    @Column(name = "pending_key_pem", columnDefinition = "TEXT")
    public String pendingKeyPem;

    @Column(name = "pending_csr_created_at")
    public java.time.Instant pendingCsrCreatedAt;

    // ACME auto-provisioning (ADR-0019). tlsMode="acme" reuses tlsCertPem/tlsKeyPem
    // above for the issued certificate (identical PEM-in-DB shape as "managed") --
    // these columns hold only the ACME-protocol state: which domain to request a
    // certificate for, the account keypair (encrypted at rest, same guarantee as
    // tlsKeyPem), the account URL ("kid") Let's Encrypt assigned on registration,
    // and status fields the Settings UI surfaces (last attempt/renewal/error).
    @Column(name = "acme_domain", length = 255)
    public String acmeDomain;

    @Column(name = "acme_account_key_pem", columnDefinition = "TEXT")
    public String acmeAccountKeyPem;

    // Public half of the account keypair (X.509 SubjectPublicKeyInfo DER, base64) --
    // stored so the JWK/thumbprint needed on every ACME request is a plain
    // KeyFactory round-trip, not re-derived from the private key.
    @Column(name = "acme_account_pub_key", length = 200)
    public String acmeAccountPubKey;

    @Column(name = "acme_account_url", length = 512)
    public String acmeAccountUrl;

    @Column(name = "acme_last_attempt_at")
    public Instant acmeLastAttemptAt;

    @Column(name = "acme_last_renewal_at")
    public Instant acmeLastRenewalAt;

    @Column(name = "acme_last_error", columnDefinition = "TEXT")
    public String acmeLastError;

    // DNS-01 challenge support (ADR-0020) — an alternative to HTTP-01 for hubs
    // that don't want port 80 reachable. "http-01" (default) | "dns-01".
    @Column(name = "acme_challenge_type", nullable = false, length = 20)
    public String acmeChallengeType = "http-01";

    // Which DnsProvider implementation to use — "cloudflare" only in v1.
    @Column(name = "acme_dns_provider", length = 50)
    public String acmeDnsProvider;

    // API token for acmeDnsProvider, encrypted at rest (same guarantee as tlsKeyPem).
    @Column(name = "acme_dns_api_token", columnDefinition = "TEXT")
    public String acmeDnsApiToken;

    // "manual" provider (no API automation) pending state — the TXT record to
    // show the admin, and the ACME order/authz/challenge/finalize URLs needed
    // to resume once they've added it and click "Continue". Non-null
    // acmeDnsPendingRecordValue is the signal that a manual DNS-01 challenge
    // is awaiting completion.
    @Column(name = "acme_dns_pending_record_name", length = 255)
    public String acmeDnsPendingRecordName;

    @Column(name = "acme_dns_pending_record_value", length = 255)
    public String acmeDnsPendingRecordValue;

    @Column(name = "acme_dns_pending_order_url", length = 512)
    public String acmeDnsPendingOrderUrl;

    @Column(name = "acme_dns_pending_authz_url", length = 512)
    public String acmeDnsPendingAuthzUrl;

    @Column(name = "acme_dns_pending_challenge_url", length = 512)
    public String acmeDnsPendingChallengeUrl;

    @Column(name = "acme_dns_pending_finalize_url", length = 512)
    public String acmeDnsPendingFinalizeUrl;

    // Optional Nominatim base URL for address geocoding in the Sites view.
    // Empty / null = geocoding disabled. No external calls are made without
    // an explicit admin-configured URL.
    @Column(name = "nominatim_url", length = 255)
    public String nominatimUrl;

    // Google Workspace Directory import (V31).
    // serviceAccountJson = full JSON key file from Google Cloud Console.
    // impersonationEmail = a Workspace admin account the SA impersonates.
    // Both null = import feature disabled.
    @Column(name = "google_ws_service_account_json", columnDefinition = "TEXT")
    public String googleWsServiceAccountJson;

    @Column(name = "google_ws_impersonation_email", length = 255)
    public String googleWsImpersonationEmail;

    // Hub location for topology map (future). All three fields are optional.
    @Column(name = "hub_lat")
    public Double hubLat;

    @Column(name = "hub_lon")
    public Double hubLon;

    @Column(name = "hub_location_label", length = 255)
    public String hubLocationLabel;

    @Column(name = "iron_rdp_enabled", nullable = false, columnDefinition = "INTEGER")
    public boolean ironRdpEnabled = false;

    // How long peer_daily_activity rows are kept before the cleanup job prunes
    // them (#32, the dashboard's connection activity heatmap). Default matches
    // the value proposed in the issue.
    @Column(name = "activity_retention_days", nullable = false)
    public int activityRetentionDays = 180;

    public boolean isPlaintextRetention() {
        return "plaintext".equalsIgnoreCase(privateKeyRetention);
    }

    public boolean isEncryptedRetention() {
        return "encrypted".equalsIgnoreCase(privateKeyRetention);
    }
}
