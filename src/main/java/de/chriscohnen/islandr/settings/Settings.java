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
    public boolean firewallDryRun = false;

    public boolean isPlaintextRetention() {
        return "plaintext".equalsIgnoreCase(privateKeyRetention);
    }
}
