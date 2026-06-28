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

    public boolean isPlaintextRetention() {
        return "plaintext".equalsIgnoreCase(privateKeyRetention);
    }

    public boolean isEncryptedRetention() {
        return "encrypted".equalsIgnoreCase(privateKeyRetention);
    }
}
