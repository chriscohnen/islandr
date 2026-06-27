package de.chriscohnen.islandr.settings;

import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.peer.Peer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class SettingsService {

    @Inject EncryptionService encSvc;

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
        String oldMode = s.privateKeyRetention;
        String newMode = req.privateKeyRetention();

        s.wgSubnet = req.wgSubnet();
        s.wgSubnet6 = (req.wgSubnet6() == null || req.wgSubnet6().isBlank()) ? null : req.wgSubnet6().strip();
        s.wgServerPublicKey = req.wgServerPublicKey();
        s.wgServerEndpoint = req.wgServerEndpoint();
        s.wgClientAllowedIps = req.wgClientAllowedIps();
        s.wgClientDns = (req.wgClientDns() == null || req.wgClientDns().isBlank())
                ? null : req.wgClientDns();
        s.privateKeyRetention = newMode;
        s.gravatarEnabled = req.gravatarEnabled();
        s.oidcAutoProvision = req.oidcAutoProvision();
        s.firewallDryRun = req.firewallDryRun();
        s.selfServicePeerCreation = req.selfServicePeerCreation();
        s.wgMtu = (req.wgMtu() != null && req.wgMtu() > 0) ? req.wgMtu() : null;
        s.wgIncludeMtuInConf = req.wgIncludeMtuInConf();
        s.nominatimUrl = (req.nominatimUrl() == null || req.nominatimUrl().isBlank())
                ? null : req.nominatimUrl().strip();
        s.hubLat = req.hubLat();
        s.hubLon = req.hubLon();
        s.hubLocationLabel = (req.hubLocationLabel() == null || req.hubLocationLabel().isBlank())
                ? null : req.hubLocationLabel().strip();
        s.updatedAt = Instant.now();
        s.updatedBy = actor;

        migrateKeys(oldMode, newMode);
        // No explicit persist() needed — Panache flushes managed entities on commit.
        return s;
    }

    @Transactional
    public Settings updateGoogleWorkspace(SettingsDto.GoogleWorkspaceRequest req, String actor) {
        Settings s = get();
        if (req.serviceAccountJson() == null || req.serviceAccountJson().isBlank()) {
            s.googleWsServiceAccountJson = null;
        } else {
            String json = req.serviceAccountJson().strip();
            s.googleWsServiceAccountJson = encSvc.isConfigured() ? encSvc.encrypt(json) : json;
        }
        s.googleWsImpersonationEmail = (req.impersonationEmail() == null || req.impersonationEmail().isBlank())
                ? null : req.impersonationEmail().strip();
        s.updatedAt = Instant.now();
        s.updatedBy = actor;
        return s;
    }

    /**
     * Re-encrypt, decrypt, or null all stored private keys when the retention mode changes.
     * Runs inside the same transaction as {@link #update} so the settings row and key
     * migration are committed atomically.
     */
    private void migrateKeys(String from, String to) {
        if (from == null || from.equals(to)) return;

        List<Peer> peers = Peer.find("privateKeyPem IS NOT NULL").list();
        if (peers.isEmpty()) return;

        if ("plaintext".equals(from) && "encrypted".equals(to)) {
            if (!encSvc.isConfigured()) {
                throw new WebApplicationException(
                        "Encrypted retention requires an encryption key — " +
                        "set ISLANDR_ENCRYPTION_KEY_PATH or ISLANDR_ENCRYPTION_KEY", 400);
            }
            peers.forEach(p -> p.privateKeyPem = encSvc.encrypt(p.privateKeyPem));

        } else if ("encrypted".equals(from) && "plaintext".equals(to)) {
            if (!encSvc.isConfigured()) {
                throw new WebApplicationException(
                        "Cannot decrypt stored keys — no encryption key configured", 400);
            }
            peers.forEach(p -> p.privateKeyPem = encSvc.decrypt(p.privateKeyPem));

        } else if ("never".equals(to)) {
            // Any mode → never: discard all stored keys.
            peers.forEach(p -> p.privateKeyPem = null);
        }
    }
}
