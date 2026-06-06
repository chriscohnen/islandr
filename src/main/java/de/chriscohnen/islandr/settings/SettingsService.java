package de.chriscohnen.islandr.settings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;

import java.time.Instant;

@ApplicationScoped
public class SettingsService {

    /**
     * The current settings row. Read-only for callers — never mutate the
     * returned entity directly; use {@link #update}.
     */
    public Settings get() {
        Settings s = Settings.findById(Settings.SINGLETON_ID);
        if (s == null) {
            // Should never happen — V3 migration seeds the row.
            throw new WebApplicationException(
                    "settings row missing — DB seed failed; check Flyway logs", 500);
        }
        return s;
    }

    @Transactional
    public Settings update(SettingsDto.UpdateRequest req, String actor) {
        Settings s = get();
        s.wgSubnet = req.wgSubnet();
        s.wgServerPublicKey = req.wgServerPublicKey();
        s.wgServerEndpoint = req.wgServerEndpoint();
        s.wgClientAllowedIps = req.wgClientAllowedIps();
        s.wgClientDns = (req.wgClientDns() == null || req.wgClientDns().isBlank())
                ? null : req.wgClientDns();
        s.privateKeyRetention = req.privateKeyRetention();
        s.gravatarEnabled = req.gravatarEnabled();
        s.oidcAutoProvision = req.oidcAutoProvision();
        s.firewallDryRun = req.firewallDryRun();
        s.selfServicePeerCreation = req.selfServicePeerCreation();
        s.updatedAt = Instant.now();
        s.updatedBy = actor;
        // No explicit persist() needed — Panache flushes managed entities on commit.
        return s;
    }
}
