package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.firewall.RulesetService;
import de.chriscohnen.islandr.proxy.EnforcementStatus;
import de.chriscohnen.islandr.proxy.ProxyUnavailableException;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import de.chriscohnen.islandr.user.User;
import de.chriscohnen.islandr.wg.WgAdapter;
import de.chriscohnen.islandr.wg.WgException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PeerService {

    private static final Logger LOG = Logger.getLogger(PeerService.class);

    @Inject WgAdapter wg;
    @Inject QrService qr;
    @Inject SettingsService settingsSvc;
    @Inject RulesetService rulesets;
    @Inject EncryptionService encSvc;
    @Inject EnforcementStatus enforcement;

    @ConfigProperty(name = "islandr.wg.interface") String wgInterface;

    @Transactional
    public PeerDto.CreateResponse createForUser(String userId, PeerDto.CreateRequest req) {
        if (userId != null) {
            User user = User.findById(userId);
            if (user == null) throw new NotFoundException("user not found: " + userId);
        }
        Settings settings = settingsSvc.get();

        validateAssignedIp(req.assignedIp(), settings.wgSubnet, null);
        validateAssignedIpv6(req.assignedIpv6(), settings.wgSubnet6, null);

        String normalisedSiteCidrs = null;
        if (req.isSite()) {
            normalisedSiteCidrs = validateSiteCidrs(req.siteAllowedCidrs(), settings.wgSubnet, null);
        } else if (req.siteAllowedCidrs() != null && !req.siteAllowedCidrs().isBlank()) {
            throw new BadRequestException(
                    "siteAllowedCidrs is only meaningful for type='site' peers — leave it empty for clients");
        }

        String publicKeyToStore;
        String privateKeyForResponse;

        if (!req.hasPublicKey() && !req.hasPrivateKey()) {
            WgAdapter.Keypair kp = wg.genKeypair();
            publicKeyToStore = kp.publicKey();
            privateKeyForResponse = kp.privateKey();
        } else if (req.hasPublicKey() && !req.hasPrivateKey()) {
            publicKeyToStore = req.publicKey();
            privateKeyForResponse = null;
        } else if (req.hasPublicKey() && req.hasPrivateKey()) {
            try {
                String derived = wg.derivePublicKey(req.privateKey());
                if (!derived.equals(req.publicKey())) {
                    throw new BadRequestException(
                            "publicKey does not match the supplied privateKey " +
                            "(derived public key differs). Double-check both fields.");
                }
            } catch (WgException e) {
                LOG.warnf("could not verify pubkey/privkey pairing — accepting blindly: %s", e.getMessage());
            }
            publicKeyToStore = req.publicKey();
            privateKeyForResponse = req.privateKey();
        } else {
            throw new BadRequestException(
                    "privateKey supplied without publicKey. Provide both, or only the publicKey, " +
                    "or neither (to have the server generate a fresh keypair).");
        }

        Peer peer = Peer.createNew(userId, req.name(), publicKeyToStore, req.assignedIp());
        peer.assignedIpv6 = emptyToNull(req.assignedIpv6());
        peer.type = req.resolvedType();
        peer.siteAllowedCidrs = normalisedSiteCidrs;
        if (peer.isSite()) {
            peer.lat = req.lat();
            peer.lng = req.lng();
            peer.locationLabel = req.locationLabel();
        }
        if (privateKeyForResponse != null) {
            if (settings.isPlaintextRetention()) {
                peer.privateKeyPem = privateKeyForResponse;
            } else if (settings.isEncryptedRetention()) {
                if (!encSvc.isConfigured()) {
                    throw new WebApplicationException(
                            "Encrypted retention is configured but no encryption key is loaded — " +
                            "set ISLANDR_ENCRYPTION_KEY_PATH or ISLANDR_ENCRYPTION_KEY", 500);
                }
                peer.privateKeyPem = encSvc.encrypt(privateKeyForResponse);
            }
        }
        if (req.deviceType() != null && !req.deviceType().isBlank()) {
            peer.deviceType = req.deviceType();
        }
        peer.mtu = (req.mtu() != null && req.mtu() > 0) ? req.mtu() : null;
        String presharedKey = null;
        if (req.generatePresharedKey()) {
            presharedKey = wg.genPsk();
            peer.presharedKey = presharedKey;
        }
        peer.persist();

        try {
            wg.setPeer(wgInterface, publicKeyToStore, hubAllowedIpsFor(peer), presharedKey);
        } catch (ProxyUnavailableException e) {
            // Enforcement plane unreachable: keep the peer persisted, mark the gap
            // honestly, and let the reconciler push it once the proxy is back (design §5).
            LOG.warnf("enforcement unavailable during setPeer for peer %s — persisted, not enforced: %s", peer.id, e.getMessage());
            enforcement.markUnavailable(e.getMessage());
        } catch (RuntimeException e) {
            LOG.errorf(e, "wg.setPeer failed for peer %s — transaction will roll back", peer.id);
            throw new WebApplicationException("could not register peer with wg: " + e.getMessage(), 500);
        }

        try {
            rulesets.recomputeAndApply("system:peer_create:" + peer.id);
        } catch (RuntimeException e) {
            LOG.errorf(e, "nftables recompute failed after wg.setPeer for peer %s — compensating", peer.id);
            try {
                wg.removePeer(wgInterface, publicKeyToStore);
            } catch (RuntimeException compensateEx) {
                LOG.errorf(compensateEx, "compensation wg.removePeer also failed for peer %s — manual cleanup required", peer.id);
            }
            throw new WebApplicationException("peer registered with wg but nftables recompute failed: " + e.getMessage(), 500);
        }

        String conf = renderConf(privateKeyForResponse, peer.assignedIp, peer.assignedIpv6, presharedKey, settings, peer.mtu, null, peer.includeDns);
        String qrPng = privateKeyForResponse != null ? qr.toDataUrl(conf) : null;

        return new PeerDto.CreateResponse(
                PeerDto.Response.from(peer),
                privateKeyForResponse,
                conf,
                qrPng,
                presharedKey);
    }

    public PeerDto.CreateResponse reshow(String peerId) {
        Settings settings = settingsSvc.get();
        Peer peer = Peer.findById(peerId);
        if (peer == null) throw new NotFoundException("peer not found: " + peerId);
        if (peer.privateKeyPem != null) {
            String rawKey = encSvc.isEncrypted(peer.privateKeyPem)
                    ? encSvc.decrypt(peer.privateKeyPem)
                    : peer.privateKeyPem;
            String conf = renderConf(rawKey, peer.assignedIp, peer.assignedIpv6, peer.presharedKey, settings, peer.mtu, peer.persistentKeepalive, peer.includeDns);
            String qrPng = qr.toDataUrl(conf);
            return new PeerDto.CreateResponse(
                    PeerDto.Response.from(peer),
                    rawKey,
                    conf,
                    qrPng,
                    peer.presharedKey);
        }
        String confNoKey = renderConf(null, peer.assignedIp, peer.assignedIpv6, peer.presharedKey, settings, peer.mtu, peer.persistentKeepalive, peer.includeDns);
        return new PeerDto.CreateResponse(
                PeerDto.Response.from(peer),
                null,
                confNoKey,
                null,
                peer.presharedKey);
    }

    /**
     * Suggest the next free IPv4 address inside the configured WireGuard subnet.
     *
     * @throws WebApplicationException 409 if every assignable address is taken.
     */
    public String suggestNextIp() {
        Settings settings = settingsSvc.get();
        IpSubnet subnet;
        try {
            subnet = IpSubnet.parse(settings.wgSubnet);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "settings.wgSubnet is invalid: " + settings.wgSubnet, 500);
        }
        java.util.Set<String> taken = Peer.<Peer>listAll().stream()
                .map(p -> p.assignedIp)
                .collect(java.util.stream.Collectors.toSet());
        for (String candidate : subnet.assignableHostIps()) {
            if (!taken.contains(candidate)) return candidate;
        }
        throw new WebApplicationException(
                Response.status(Response.Status.CONFLICT)
                        .entity("no free IP available in subnet " + settings.wgSubnet)
                        .build());
    }

    /**
     * Suggest the next free IPv6 address inside the configured {@code wgSubnet6}.
     *
     * @throws WebApplicationException 412 if wgSubnet6 is not configured, 409 if exhausted.
     */
    public String suggestNextIpv6() {
        Settings settings = settingsSvc.get();
        if (settings.wgSubnet6 == null || settings.wgSubnet6.isBlank()) {
            throw new WebApplicationException(
                    Response.status(Response.Status.PRECONDITION_FAILED)
                            .entity("wgSubnet6 is not configured — set it in Settings first")
                            .build());
        }
        IpSubnet subnet;
        try {
            subnet = IpSubnet.parse(settings.wgSubnet6);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "settings.wgSubnet6 is invalid: " + settings.wgSubnet6, 500);
        }
        java.util.Set<String> taken = Peer.<Peer>listAll().stream()
                .filter(p -> p.assignedIpv6 != null)
                .map(p -> p.assignedIpv6)
                .collect(java.util.stream.Collectors.toSet());
        for (String candidate : subnet.assignableHostIps()) {
            if (!taken.contains(candidate)) return candidate;
        }
        throw new WebApplicationException(
                Response.status(Response.Status.CONFLICT)
                        .entity("no free IPv6 available in subnet " + settings.wgSubnet6)
                        .build());
    }

    @Transactional
    public PeerDto.CreateResponse update(String peerId, PeerDto.UpdateRequest req) {
        Settings settings = settingsSvc.get();
        Peer peer = Peer.findById(peerId);
        if (peer == null) throw new NotFoundException("peer not found: " + peerId);

        boolean ipChanged = !peer.assignedIp.equals(req.assignedIp());
        if (ipChanged) {
            validateAssignedIp(req.assignedIp(), settings.wgSubnet, peer.id);
        }

        String newIpv6 = emptyToNull(req.assignedIpv6());
        boolean ip6Changed = !java.util.Objects.equals(peer.assignedIpv6, newIpv6);
        if (ip6Changed && newIpv6 != null) {
            validateAssignedIpv6(newIpv6, settings.wgSubnet6, peer.id);
        }

        String normalisedCidrs;
        if (peer.isSite()) {
            normalisedCidrs = validateSiteCidrs(req.siteAllowedCidrs(), settings.wgSubnet, peer.id);
        } else {
            if (req.siteAllowedCidrs() != null && !req.siteAllowedCidrs().isBlank()) {
                throw new BadRequestException("siteAllowedCidrs is only meaningful for type='site' peers");
            }
            normalisedCidrs = null;
        }
        boolean cidrsChanged = !java.util.Objects.equals(peer.siteAllowedCidrs, normalisedCidrs);

        boolean pskChanged = false;
        String pskForWg = null;
        if ("rotate".equals(req.presharedKeyAction())) {
            String newPsk = wg.genPsk();
            peer.presharedKey = newPsk;
            peer.pskRotatedAt = java.time.Instant.now();
            pskForWg = newPsk;
            pskChanged = true;
        } else if ("remove".equals(req.presharedKeyAction())) {
            peer.presharedKey = null;
            pskForWg = "";
            pskChanged = true;
        }

        peer.name = req.name();
        peer.assignedIp = req.assignedIp();
        peer.assignedIpv6 = newIpv6;
        peer.siteAllowedCidrs = normalisedCidrs;
        if (req.deviceType() != null && !req.deviceType().isBlank()) {
            peer.deviceType = req.deviceType();
        } else if (req.deviceType() != null) {
            peer.deviceType = null;
        }
        if (peer.isSite()) {
            peer.lat = req.lat();
            peer.lng = req.lng();
            peer.locationLabel = req.locationLabel();
        } else {
            peer.lat = null;
            peer.lng = null;
            peer.locationLabel = null;
        }
        peer.mtu = (req.mtu() != null && req.mtu() > 0) ? req.mtu() : null;
        // Assigned directly (not `> 0 ? x : null` like mtu): 0 is a meaningful
        // "keepalive off for this peer" override, distinct from null = defer to global.
        peer.persistentKeepalive = req.persistentKeepalive();
        // null (field omitted) keeps the current/default true; only an explicit
        // false turns off the DNS line for this peer.
        peer.includeDns = req.includeDns() == null || req.includeDns();
        peer.validUntil = req.validUntil();
        peer.updatedAt = java.time.Instant.now();
        peer.persist();

        if ((ipChanged || ip6Changed || cidrsChanged || pskChanged) && peer.enabled) {
            try {
                wg.setPeer(wgInterface, peer.publicKey, hubAllowedIpsFor(peer),
                        pskChanged ? pskForWg : null);
            } catch (ProxyUnavailableException e) {
                LOG.warnf("enforcement unavailable during update setPeer for peer %s — persisted, not enforced: %s", peer.id, e.getMessage());
                enforcement.markUnavailable(e.getMessage());
            } catch (RuntimeException e) {
                LOG.errorf(e, "wg.setPeer failed for updated peer %s", peer.id);
                throw new WebApplicationException(
                        "could not update peer on wg: " + e.getMessage(), 500);
            }
        }

        String rawKey = (peer.privateKeyPem != null && encSvc.isEncrypted(peer.privateKeyPem))
                ? encSvc.decrypt(peer.privateKeyPem)
                : peer.privateKeyPem;
        String conf = renderConf(rawKey, peer.assignedIp, peer.assignedIpv6, peer.presharedKey, settings, peer.mtu, peer.persistentKeepalive, peer.includeDns);
        String qrPng = rawKey != null ? qr.toDataUrl(conf) : null;
        return new PeerDto.CreateResponse(
                PeerDto.Response.from(peer),
                rawKey,
                conf,
                qrPng,
                peer.presharedKey);
    }

    @Transactional
    public PeerDto.Response rotatePublicKey(String peerId, String newPublicKey) {
        Peer peer = Peer.findById(peerId);
        if (peer == null) throw new NotFoundException("peer not found: " + peerId);
        if (newPublicKey == null || newPublicKey.isBlank()) {
            throw new BadRequestException("publicKey is required");
        }
        if (newPublicKey.equals(peer.publicKey)) {
            return PeerDto.Response.from(peer);
        }
        long taken = Peer.count("publicKey = ?1 and id <> ?2", newPublicKey, peerId);
        if (taken > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("publicKey is already used by another peer")
                            .build());
        }
        String oldKey = peer.publicKey;
        peer.publicKey = newPublicKey;
        peer.privateKeyPem = null;
        peer.updatedAt = java.time.Instant.now();
        peer.persist();

        try {
            wg.removePeer(wgInterface, oldKey);
            if (peer.enabled) {
                wg.setPeer(wgInterface, peer.publicKey, hubAllowedIpsFor(peer), null);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "wg key rotation failed for peer %s", peer.id);
            throw new WebApplicationException("could not rotate key on wg: " + e.getMessage(), 500);
        }
        return PeerDto.Response.from(peer);
    }

    /**
     * Generates a fresh keypair server-side and replaces this peer's identity
     * on the hub (issue #46) — an admin-triggered alternative to delete-and-
     * recreate for a suspected-compromised device. Unlike the self-service
     * {@link #rotatePublicKey}, the server generates *both* halves of the
     * keypair (mirroring peer creation) and returns the private key once, so
     * the caller can show a fresh .conf/QR immediately. Respects the
     * configured retention mode exactly like {@link #createForUser} does.
     */
    @Transactional
    public PeerDto.CreateResponse rotateAdminKey(String peerId) {
        Settings settings = settingsSvc.get();
        Peer peer = Peer.findById(peerId);
        if (peer == null) throw new NotFoundException("peer not found: " + peerId);

        WgAdapter.Keypair kp = wg.genKeypair();
        String oldKey = peer.publicKey;
        peer.publicKey = kp.publicKey();
        peer.privateKeyPem = null;
        if (settings.isPlaintextRetention()) {
            peer.privateKeyPem = kp.privateKey();
        } else if (settings.isEncryptedRetention()) {
            if (!encSvc.isConfigured()) {
                throw new WebApplicationException(
                        "Encrypted retention is configured but no encryption key is loaded — " +
                        "set ISLANDR_ENCRYPTION_KEY_PATH or ISLANDR_ENCRYPTION_KEY", 500);
            }
            peer.privateKeyPem = encSvc.encrypt(kp.privateKey());
        }
        peer.keyRotatedAt = java.time.Instant.now();
        peer.updatedAt = peer.keyRotatedAt;
        peer.persist();

        try {
            wg.removePeer(wgInterface, oldKey);
            if (peer.enabled) {
                // Unlike rotatePublicKey, pass the peer's existing PSK through —
                // removing+re-adding the wg entry must not silently drop it.
                wg.setPeer(wgInterface, peer.publicKey, hubAllowedIpsFor(peer), peer.presharedKey);
            }
        } catch (ProxyUnavailableException e) {
            // Same graceful-degradation contract as createForUser/update/delete
            // (design §5): the new keypair is already persisted above, so an
            // unreachable proxy must not discard it — this is precisely the
            // incident-response case (issue #46) where the admin needs the
            // fresh .conf/QR regardless of whether the hub can apply it right
            // now; the reconciler pushes it once the proxy is back.
            LOG.warnf("enforcement unavailable during admin key rotation for peer %s — persisted, not enforced: %s", peer.id, e.getMessage());
            enforcement.markUnavailable(e.getMessage());
        } catch (RuntimeException e) {
            LOG.errorf(e, "wg admin key rotation failed for peer %s", peer.id);
            throw new WebApplicationException("could not rotate key on wg: " + e.getMessage(), 500);
        }

        String conf = renderConf(kp.privateKey(), peer.assignedIp, peer.assignedIpv6, peer.presharedKey, settings, peer.mtu, peer.persistentKeepalive, peer.includeDns);
        String qrPng = qr.toDataUrl(conf);
        return new PeerDto.CreateResponse(
                PeerDto.Response.from(peer),
                kp.privateKey(),
                conf,
                qrPng,
                peer.presharedKey);
    }

    @Transactional
    public void delete(String peerId) {
        Peer peer = Peer.findById(peerId);
        if (peer == null) throw new NotFoundException("peer not found: " + peerId);
        try {
            wg.removePeer(wgInterface, peer.publicKey);
        } catch (ProxyUnavailableException e) {
            LOG.warnf("enforcement unavailable during delete removePeer for %s — deleting DB row anyway: %s", peerId, e.getMessage());
            enforcement.markUnavailable(e.getMessage());
        } catch (RuntimeException e) {
            LOG.warnf("wg.removePeer failed for %s; deleting DB row anyway: %s", peerId, e.getMessage());
        }
        peer.delete();
    }

    /** Admin-initiated enable/disable via the API — marks {@code enabledSource="manual"}
     *  (#47) so PeerScheduleJob won't undo it until the schedule's next transition. */
    @Transactional
    public PeerDto.Response setEnabled(String peerId, boolean enabled) {
        return setEnabled(peerId, enabled, "manual");
    }

    /** Scheduler-initiated enable/disable (#47) — marks {@code enabledSource="schedule"}. */
    @Transactional
    public PeerDto.Response setEnabledBySchedule(String peerId, boolean enabled) {
        return setEnabled(peerId, enabled, "schedule");
    }

    private PeerDto.Response setEnabled(String peerId, boolean enabled, String source) {
        Peer peer = Peer.findById(peerId);
        if (peer == null) throw new NotFoundException("peer not found: " + peerId);
        if (peer.enabled == enabled) {
            peer.enabledSource = source;
            return PeerDto.Response.from(peer);
        }
        peer.enabled = enabled;
        peer.enabledSource = source;
        peer.updatedAt = java.time.Instant.now();
        if (enabled) {
            wg.setPeer(wgInterface, peer.publicKey, hubAllowedIpsFor(peer), peer.presharedKey);
        } else {
            wg.removePeer(wgInterface, peer.publicKey);
        }
        return PeerDto.Response.from(peer);
    }

    /**
     * Re-push every enabled peer to the WireGuard interface. Used by the proxy
     * reconciler after the enforcement plane comes back (design §6): a full
     * re-apply, not a delta, so the live interface converges to DB state.
     *
     * <p>One peer's rejection (bad stored data, etc.) does not stop the rest of
     * the batch from being pushed — it's logged and skipped instead, so a single
     * broken peer can't silently strand every peer after it in DB order.
     *
     * <p>Propagates {@link ProxyUnavailableException} if the proxy drops
     * mid-reconcile — the reconciler catches it and re-enters the degraded state.
     * That one does abort the batch: if the proxy itself is gone, every
     * remaining call would fail the same way anyway.
     */
    @Transactional
    public void repushEnabledPeers() {
        for (Peer peer : Peer.<Peer>list("enabled", true)) {
            try {
                wg.setPeer(wgInterface, peer.publicKey, hubAllowedIpsFor(peer), peer.presharedKey);
            } catch (ProxyUnavailableException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.errorf(e, "repush failed for peer %s — skipping, remaining peers still processed", peer.id);
            }
        }
    }

    /**
     * Hub-side AllowedIPs: peer's own /32 (and /128 when dual-stack),
     * plus downstream CIDRs for site peers.
     */
    private static String hubAllowedIpsFor(Peer peer) {
        StringBuilder sb = new StringBuilder(peer.assignedIp).append("/32");
        if (peer.assignedIpv6 != null && !peer.assignedIpv6.isBlank()) {
            sb.append(",").append(peer.assignedIpv6).append("/128");
        }
        if (peer.isSite() && peer.siteAllowedCidrs != null && !peer.siteAllowedCidrs.isBlank()) {
            // siteAllowedCidrs is stored "cidr1, cidr2, ..." (comma+space) for
            // human-readable display (validateSiteCidrs joins with ", "). The
            // wire value the proxy parses has no tolerance for whitespace
            // (net.ParseCIDR rejects a leading space), so strip it here rather
            // than loosen the proxy's intentionally strict parser.
            sb.append(",").append(peer.siteAllowedCidrs.replace(" ", ""));
        }
        return sb.toString();
    }

    private static String validateSiteCidrs(String raw, String wgSubnet, String excludePeerId) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(
                    "siteAllowedCidrs must list at least one CIDR for type='site' peers");
        }
        IpSubnet wg = IpSubnet.parse(wgSubnet);
        String[] parts = raw.split(",");
        java.util.List<String> normalised = new java.util.ArrayList<>(parts.length);
        java.util.List<IpSubnet> parsed = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            String cidr = part.trim();
            if (cidr.isEmpty()) continue;
            IpSubnet s;
            try {
                s = IpSubnet.parse(cidr);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("invalid CIDR in siteAllowedCidrs: " + cidr);
            }
            if (s.overlaps(wg)) {
                throw new BadRequestException(
                        "site CIDR " + cidr + " overlaps the WireGuard subnet " + wgSubnet +
                        " — pick a network that is not already managed by the tunnel itself");
            }
            normalised.add(cidr);
            parsed.add(s);
        }
        if (normalised.isEmpty()) throw new BadRequestException("siteAllowedCidrs is empty after trimming");
        for (int i = 0; i < parsed.size(); i++) {
            for (int j = i + 1; j < parsed.size(); j++) {
                if (parsed.get(i).overlaps(parsed.get(j))) {
                    throw new BadRequestException(
                            "site CIDRs " + normalised.get(i) + " and " + normalised.get(j) + " overlap each other");
                }
            }
        }
        java.util.List<Peer> siteSiblings = Peer.list("type = ?1", "site");
        for (Peer other : siteSiblings) {
            if (excludePeerId != null && excludePeerId.equals(other.id)) continue;
            if (other.siteAllowedCidrs == null || other.siteAllowedCidrs.isBlank()) continue;
            for (String otherRaw : other.siteAllowedCidrs.split(",")) {
                String otherCidr = otherRaw.trim();
                if (otherCidr.isEmpty()) continue;
                IpSubnet otherSubnet;
                try {
                    otherSubnet = IpSubnet.parse(otherCidr);
                } catch (Exception e) {
                    LOG.warnf("ignoring malformed stored site CIDR on peer %s: %s", other.id, otherCidr);
                    continue;
                }
                for (int i = 0; i < parsed.size(); i++) {
                    if (parsed.get(i).overlaps(otherSubnet)) {
                        throw new BadRequestException(
                                "site CIDR " + normalised.get(i) +
                                " overlaps " + otherCidr +
                                " already declared on peer '" + other.name + "'");
                    }
                }
            }
        }
        return String.join(", ", normalised);
    }

    private void validateAssignedIp(String ip, String wgSubnet) {
        validateAssignedIp(ip, wgSubnet, null);
    }

    private void validateAssignedIp(String ip, String wgSubnet, String excludePeerId) {
        IpSubnet subnet;
        try {
            subnet = IpSubnet.parse(wgSubnet);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "settings.wgSubnet is invalid: " + wgSubnet + " — fix it in the Admin Console", 500);
        }
        try {
            if (!subnet.contains(ip)) {
                throw new BadRequestException("assigned IP " + ip + " is outside the wg subnet " + wgSubnet);
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid IP address: " + ip);
        }
        long existing = excludePeerId == null
                ? Peer.count("assignedIp = ?1", ip)
                : Peer.count("assignedIp = ?1 and id <> ?2", ip, excludePeerId);
        if (existing > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("IP " + ip + " is already assigned to another peer")
                            .build());
        }
    }

    private void validateAssignedIpv6(String ip6, String wgSubnet6, String excludePeerId) {
        if (ip6 == null || ip6.isBlank()) return;
        if (wgSubnet6 == null || wgSubnet6.isBlank()) {
            throw new BadRequestException(
                    "assignedIpv6 was provided but wgSubnet6 is not configured in Settings");
        }
        IpSubnet subnet;
        try {
            subnet = IpSubnet.parse(wgSubnet6);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "settings.wgSubnet6 is invalid: " + wgSubnet6, 500);
        }
        try {
            if (!subnet.contains(ip6)) {
                throw new BadRequestException("assigned IPv6 " + ip6 + " is outside the wg6 subnet " + wgSubnet6);
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid IPv6 address: " + ip6);
        }
        long existing = excludePeerId == null
                ? Peer.count("assignedIpv6 = ?1", ip6)
                : Peer.count("assignedIpv6 = ?1 and id <> ?2", ip6, excludePeerId);
        if (existing > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("IPv6 " + ip6 + " is already assigned to another peer")
                            .build());
        }
    }

    private String renderConf(String privateKey, String assignedIp, String assignedIpv6,
                               String presharedKey, Settings settings, Integer peerMtu,
                               Integer peerKeepalive, boolean peerIncludeDns) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Interface]\n");
        if (privateKey != null) {
            sb.append("PrivateKey = ").append(privateKey).append("\n");
        }
        if (assignedIpv6 != null && !assignedIpv6.isBlank()) {
            sb.append("Address = ").append(assignedIp).append("/32,").append(assignedIpv6).append("/128\n");
        } else {
            sb.append("Address = ").append(assignedIp).append("/32\n");
        }
        String effectiveDns = settings.effectiveClientDns();
        if (peerIncludeDns && effectiveDns != null && !effectiveDns.isBlank()) {
            sb.append("DNS = ").append(effectiveDns).append("\n");
        }
        Integer effectiveMtu = peerMtu != null
                ? peerMtu
                : (settings.wgIncludeMtuInConf && settings.wgMtu != null && settings.wgMtu > 0
                    ? settings.wgMtu : null);
        if (effectiveMtu != null) {
            sb.append("MTU = ").append(effectiveMtu).append("\n");
        }

        String allowedIps = AllowedIpsCalculator.compute(
                settings.tunnelMode, settings.allowedIpsMode, settings.wgClientAllowedIps,
                settings.wgSubnet, settings.wgSubnet6, settings.splitSupernet,
                de.chriscohnen.islandr.acl.Site.enabledGatewayCidrs(),
                effectiveDns, peerIncludeDns);

        sb.append("\n[Peer]\n");
        sb.append("PublicKey = ").append(settings.wgServerPublicKey).append("\n");
        if (presharedKey != null && !presharedKey.isBlank()) {
            sb.append("PresharedKey = ").append(presharedKey).append("\n");
        }
        sb.append("AllowedIPs = ").append(allowedIps).append("\n");
        sb.append("Endpoint = ").append(settings.wgServerEndpoint).append("\n");
        // Effective keepalive: per-peer override wins, else the global default.
        // The value is the switch — omit the line entirely when it resolves to 0.
        int effectiveKeepalive = peerKeepalive != null ? peerKeepalive : settings.wgPersistentKeepalive;
        if (effectiveKeepalive > 0) {
            sb.append("PersistentKeepalive = ").append(effectiveKeepalive).append("\n");
        }

        return sb.toString();
    }

    public java.util.List<PeerDto.WgImportCandidate> wgImportPreview() {
        java.util.List<WgAdapter.PeerStatus> live = wg.showPeers(wgInterface);
        java.util.Set<String> existingKeys = Peer.<Peer>listAll()
                .stream().map(p -> p.publicKey).collect(java.util.stream.Collectors.toSet());
        return live.stream().map(ps -> {
            String ip4 = extractFirstIpv4(ps.allowedIps());
            String ip6 = extractFirstIpv6(ps.allowedIps());
            boolean skip = existingKeys.contains(ps.publicKey()) || (ip4 == null && ip6 == null);
            return new PeerDto.WgImportCandidate(
                    ps.publicKey(),
                    ps.allowedIps(),
                    ip4,
                    ip6,
                    ps.endpoint(),
                    skip);
        }).toList();
    }

    private static String extractFirstIpv4(String allowedIps) {
        if (allowedIps == null || allowedIps.isBlank()) return null;
        for (String entry : allowedIps.split(",")) {
            String addr = entry.trim();
            if (addr.contains("/")) addr = addr.substring(0, addr.indexOf('/'));
            if (addr.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return addr;
        }
        return null;
    }

    private static String extractFirstIpv6(String allowedIps) {
        if (allowedIps == null || allowedIps.isBlank()) return null;
        for (String entry : allowedIps.split(",")) {
            String addr = entry.trim();
            if (addr.contains("/")) addr = addr.substring(0, addr.lastIndexOf('/'));
            if (addr.contains(":")) return addr;
        }
        return null;
    }

    @Transactional
    public java.util.List<PeerDto.WgImportResult> wgImport(java.util.List<PeerDto.WgImportEntry> entries) {
        Settings settings = settingsSvc.get();
        java.util.List<PeerDto.WgImportResult> results = new java.util.ArrayList<>();
        for (PeerDto.WgImportEntry e : entries) {
            if (Peer.find("publicKey", e.publicKey()).count() > 0) {
                results.add(new PeerDto.WgImportResult(e.publicKey(), "skipped", null));
                continue;
            }
            validateAssignedIp(e.assignedIp(), settings.wgSubnet);
            String type = (e.type() == null || e.type().isBlank()) ? "client" : e.type();
            Peer p = Peer.createNew(e.userId(), e.name(), e.publicKey(), e.assignedIp());
            p.type = type;
            p.persist();
            LOG.infof("wg-import: created peer %s (%s) ip=%s", p.name, p.id, p.assignedIp);
            results.add(new PeerDto.WgImportResult(e.publicKey(), "imported", p.id));
        }
        return results;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
