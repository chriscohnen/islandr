package de.chriscohnen.islandr.peer;

import de.chriscohnen.islandr.firewall.RulesetService;
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

    // Bootstrap-only — see ADR-0008. The interface name is set in the systemd
    // unit / docker-compose, not editable at runtime.
    @ConfigProperty(name = "islandr.wg.interface") String wgInterface;

    @Transactional
    public PeerDto.CreateResponse createForUser(String userId, PeerDto.CreateRequest req) {
        User user = User.findById(userId);
        if (user == null) {
            throw new NotFoundException("user not found: " + userId);
        }
        Settings settings = settingsSvc.get();

        validateAssignedIp(req.assignedIp(), settings.wgSubnet);

        // Site peers need a CIDR list — and the CIDRs must not collide with the
        // WG subnet itself or with any other site peer's reach. Done before the
        // keypair branch so a validation failure never burns a wg.genKeypair() call.
        String normalisedSiteCidrs = null;
        if (req.isSite()) {
            normalisedSiteCidrs = validateSiteCidrs(req.siteAllowedCidrs(), settings.wgSubnet, null);
        } else if (req.siteAllowedCidrs() != null && !req.siteAllowedCidrs().isBlank()) {
            throw new BadRequestException(
                    "siteAllowedCidrs is only meaningful for type='site' peers — leave it empty for clients");
        }

        // Resolve the keypair from the three import modes. publicKeyToStore is
        // always set; privateKeyForResponse may be null (admin imported a
        // client-generated public key and the server never sees the private key).
        String publicKeyToStore;
        String privateKeyForResponse;

        if (!req.hasPublicKey() && !req.hasPrivateKey()) {
            // Default path: server generates the whole keypair.
            WgAdapter.Keypair kp = wg.genKeypair();
            publicKeyToStore = kp.publicKey();
            privateKeyForResponse = kp.privateKey();
        } else if (req.hasPublicKey() && !req.hasPrivateKey()) {
            // Public-only import: client generated their own key, only the
            // public half ever reaches the server. Reshow/.conf will be served
            // without a PrivateKey line.
            publicKeyToStore = req.publicKey();
            privateKeyForResponse = null;
        } else if (req.hasPublicKey() && req.hasPrivateKey()) {
            // Full import (e.g. PiVPN migration). Validate that the two halves
            // actually pair before persisting — saves the admin from a silent
            // misconfig that would only surface when the client fails to connect.
            try {
                String derived = wg.derivePublicKey(req.privateKey());
                if (!derived.equals(req.publicKey())) {
                    throw new BadRequestException(
                            "publicKey does not match the supplied privateKey " +
                            "(derived public key differs). Double-check both fields.");
                }
            } catch (WgException e) {
                // Adapter could not derive (e.g. wg CLI missing). Log and accept —
                // we'd rather let the admin proceed than block on environment quirks.
                LOG.warnf("could not verify pubkey/privkey pairing — accepting blindly: %s", e.getMessage());
            }
            publicKeyToStore = req.publicKey();
            privateKeyForResponse = req.privateKey();
        } else {
            // privateKey without publicKey is ambiguous: we could derive, but the
            // admin should be explicit about both halves. Refuse.
            throw new BadRequestException(
                    "privateKey supplied without publicKey. Provide both, or only the publicKey, " +
                    "or neither (to have the server generate a fresh keypair).");
        }

        Peer peer = Peer.createNew(user.id, req.name(), publicKeyToStore, req.assignedIp());
        peer.type = req.resolvedType();
        peer.siteAllowedCidrs = normalisedSiteCidrs;
        if (settings.isPlaintextRetention() && privateKeyForResponse != null) {
            // R-060 (ADR-0007): operator opted in to plaintext retention.
            peer.privateKeyPem = privateKeyForResponse;
        }
        if (req.deviceType() != null && !req.deviceType().isBlank()) {
            peer.deviceType = req.deviceType();
        }
        peer.persist();

        // Saga step 1 — register peer with WireGuard kernel. If this fails the
        // DB transaction will roll back (we're still inside @Transactional).
        try {
            wg.setPeer(wgInterface, publicKeyToStore, hubAllowedIpsFor(peer));
        } catch (RuntimeException e) {
            LOG.errorf(e, "wg.setPeer failed for peer %s — transaction will roll back", peer.id);
            throw new WebApplicationException("could not register peer with wg: " + e.getMessage(), 500);
        }

        // Saga step 2 — recompute nftables so the new peer immediately has the
        // rules that match its grants. If this fails we compensate step 1 by
        // removing the peer from WireGuard so both kernel states agree with
        // what the DB will reflect after the transaction rolls back.
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

        // Render conf + QR only when we actually have a private key to embed.
        // Public-only import gets the keyless conf shape (same as never-retention reshow).
        String conf = renderConf(privateKeyForResponse, req.assignedIp(), settings);
        String qrPng = privateKeyForResponse != null ? qr.toDataUrl(conf) : null;

        return new PeerDto.CreateResponse(
                PeerDto.Response.from(peer),
                privateKeyForResponse,
                conf,
                qrPng);
    }

    /**
     * Re-render the .conf for an existing peer.
     *
     * <p>If the peer has a stored private key (retention=plaintext at create time),
     * the response includes the key, a complete .conf and a QR code — same shape as
     * the original create response.
     *
     * <p>If no key is stored, the response still carries a .conf containing the
     * server-side parameters (Address, DNS, server PublicKey, AllowedIPs, Endpoint)
     * — without a {@code PrivateKey} line. {@code privateKey} and {@code qrPngBase64}
     * are {@code null}. The user can paste their key manually or use the .conf as a
     * template for a fresh peer.
     */
    public PeerDto.CreateResponse reshow(String peerId) {
        Settings settings = settingsSvc.get();
        Peer peer = Peer.findById(peerId);
        if (peer == null) {
            throw new NotFoundException("peer not found: " + peerId);
        }
        if (peer.privateKeyPem != null) {
            String conf = renderConf(peer.privateKeyPem, peer.assignedIp, settings);
            String qrPng = qr.toDataUrl(conf);
            return new PeerDto.CreateResponse(
                    PeerDto.Response.from(peer),
                    peer.privateKeyPem,
                    conf,
                    qrPng);
        }
        String confNoKey = renderConf(null, peer.assignedIp, settings);
        return new PeerDto.CreateResponse(
                PeerDto.Response.from(peer),
                null,
                confNoKey,
                null);
    }

    /**
     * Suggest the smallest free IPv4 host address inside the configured WireGuard
     * subnet, skipping addresses already taken by other peers.
     *
     * <p>This is best-effort: between the suggestion and the actual create call
     * a different request can still grab the same IP, in which case
     * {@link #createForUser} will reject with 409. The UI is expected to handle
     * that race the same as any other duplicate-IP rejection.
     *
     * @throws WebApplicationException 409 if every assignable address is taken,
     *         or 500 if {@code settings.wgSubnet} cannot be parsed.
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
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        throw new WebApplicationException(
                Response.status(Response.Status.CONFLICT)
                        .entity("no free IP available in subnet " + settings.wgSubnet)
                        .build());
    }

    /**
     * Edit a peer's mutable fields (name, assignedIp, siteAllowedCidrs).
     * Public key and type are not editable here — delete + recreate for those.
     *
     * <p>If the new AllowedIPs differ from what's on the wire (because IP or
     * site CIDRs changed), pushes a {@code wg set peer} so the kernel state
     * tracks the DB. The client needs to re-import the .conf in that case;
     * the returned {@link PeerDto.CreateResponse} carries a fresh conf + QR
     * for that.
     */
    @Transactional
    public PeerDto.CreateResponse update(String peerId, PeerDto.UpdateRequest req) {
        Settings settings = settingsSvc.get();
        Peer peer = Peer.findById(peerId);
        if (peer == null) {
            throw new NotFoundException("peer not found: " + peerId);
        }

        boolean ipChanged = !peer.assignedIp.equals(req.assignedIp());
        if (ipChanged) {
            validateAssignedIp(req.assignedIp(), settings.wgSubnet, peer.id);
        }

        String normalisedCidrs;
        if (peer.isSite()) {
            // Required + validated against WG subnet + against other site peers,
            // excluding this peer's own current CIDRs from the overlap check.
            normalisedCidrs = validateSiteCidrs(req.siteAllowedCidrs(), settings.wgSubnet, peer.id);
        } else {
            if (req.siteAllowedCidrs() != null && !req.siteAllowedCidrs().isBlank()) {
                throw new BadRequestException(
                        "siteAllowedCidrs is only meaningful for type='site' peers");
            }
            normalisedCidrs = null;
        }
        boolean cidrsChanged = !java.util.Objects.equals(peer.siteAllowedCidrs, normalisedCidrs);

        peer.name = req.name();
        peer.assignedIp = req.assignedIp();
        peer.siteAllowedCidrs = normalisedCidrs;
        if (req.deviceType() != null && !req.deviceType().isBlank()) {
            peer.deviceType = req.deviceType();
        } else if (req.deviceType() != null) {
            peer.deviceType = null;  // explicit empty string clears the field
        }
        peer.persist();

        if ((ipChanged || cidrsChanged) && peer.enabled) {
            try {
                // wg merges by public key, so a setPeer with the new allowed-ips
                // is enough — no remove first.
                wg.setPeer(wgInterface, peer.publicKey, hubAllowedIpsFor(peer));
            } catch (RuntimeException e) {
                LOG.errorf(e, "wg.setPeer failed for updated peer %s", peer.id);
                throw new WebApplicationException(
                        "could not update peer on wg: " + e.getMessage(), 500);
            }
        }

        // Same response shape as create/reshow so the UI can treat it uniformly.
        // If a private key was stored, we can also rebuild the QR; otherwise the
        // conf is served keyless.
        String conf = renderConf(peer.privateKeyPem, peer.assignedIp, settings);
        String qrPng = peer.privateKeyPem != null ? qr.toDataUrl(conf) : null;
        return new PeerDto.CreateResponse(
                PeerDto.Response.from(peer),
                peer.privateKeyPem,
                conf,
                qrPng);
    }

    /**
     * Replace a peer's public key (and drop any retained private key — the
     * server can no longer reach what's behind the new key). Used by the
     * self-service flow: a user rotates the key on their device and pushes the
     * new public half here. The kernel needs to learn the new key for traffic
     * to still flow, so we remove + re-add the peer with the same allowed-ips.
     */
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
        peer.privateKeyPem = null;  // server no longer holds the matching half
        peer.persist();

        try {
            wg.removePeer(wgInterface, oldKey);
            if (peer.enabled) {
                wg.setPeer(wgInterface, peer.publicKey, hubAllowedIpsFor(peer));
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "wg key rotation failed for peer %s", peer.id);
            throw new WebApplicationException(
                    "could not rotate key on wg: " + e.getMessage(), 500);
        }
        return PeerDto.Response.from(peer);
    }

    @Transactional
    public void delete(String peerId) {
        Peer peer = Peer.findById(peerId);
        if (peer == null) {
            throw new NotFoundException("peer not found: " + peerId);
        }
        try {
            wg.removePeer(wgInterface, peer.publicKey);
        } catch (RuntimeException e) {
            LOG.warnf("wg.removePeer failed for %s; deleting DB row anyway: %s", peerId, e.getMessage());
        }
        peer.delete();
    }

    @Transactional
    public PeerDto.Response setEnabled(String peerId, boolean enabled) {
        Peer peer = Peer.findById(peerId);
        if (peer == null) {
            throw new NotFoundException("peer not found: " + peerId);
        }
        if (peer.enabled == enabled) {
            return PeerDto.Response.from(peer);
        }
        peer.enabled = enabled;
        if (enabled) {
            wg.setPeer(wgInterface, peer.publicKey, hubAllowedIpsFor(peer));
        } else {
            wg.removePeer(wgInterface, peer.publicKey);
        }
        return PeerDto.Response.from(peer);
    }

    /**
     * Hub-side AllowedIPs for this peer: always the peer's own /32, plus the
     * downstream CIDRs declared on a site peer. This is the value the kernel
     * uses to decide which packets to encrypt to this peer.
     */
    private static String hubAllowedIpsFor(Peer peer) {
        if (peer.isSite() && peer.siteAllowedCidrs != null && !peer.siteAllowedCidrs.isBlank()) {
            return peer.assignedIp + "/32," + peer.siteAllowedCidrs;
        }
        return peer.assignedIp + "/32";
    }

    /**
     * Validate the site CIDR list: format, no overlap with the WG subnet, no
     * overlap across multiple entries in this list, and no overlap with any
     * other site peer's already-declared CIDRs.
     *
     * @param raw raw user input, expected as comma-separated IPv4 CIDRs.
     * @param wgSubnet the WireGuard subnet (e.g. {@code 10.8.0.0/24}).
     * @param excludePeerId peer ID to skip in the cross-peer overlap check
     *        (set on update to avoid the peer flagging itself); {@code null}
     *        on create.
     * @return normalised CIDR list (trimmed, no empty entries, single spaces
     *         around commas) — ready to persist as-is.
     */
    private static String validateSiteCidrs(String raw, String wgSubnet, String excludePeerId) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException(
                    "siteAllowedCidrs must list at least one CIDR for type='site' peers");
        }
        IpSubnet wg = IpSubnet.parse(wgSubnet);

        // Parse + format-validate each entry. Collect the IpSubnet objects so
        // we can do intra-list and cross-peer overlap checks below.
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
        if (normalised.isEmpty()) {
            throw new BadRequestException("siteAllowedCidrs is empty after trimming");
        }
        // Intra-list overlap: an admin who writes "10.20.0.0/16, 10.20.5.0/24"
        // is contradicting themselves.
        for (int i = 0; i < parsed.size(); i++) {
            for (int j = i + 1; j < parsed.size(); j++) {
                if (parsed.get(i).overlaps(parsed.get(j))) {
                    throw new BadRequestException(
                            "site CIDRs " + normalised.get(i) + " and " + normalised.get(j) +
                            " overlap each other");
                }
            }
        }
        // Cross-peer overlap: every existing site peer's CIDR list (minus self
        // when updating) must be disjoint from this one. Otherwise the kernel
        // can't decide which peer's tunnel to route a packet through.
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
                    // Stored data is malformed — log and skip rather than blow up
                    // a new admin action because of legacy garbage.
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

    /**
     * @param excludePeerId peer ID to skip in the duplicate-IP check; pass the
     *        edited peer's own ID on update so it doesn't flag itself.
     */
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
            throw new BadRequestException("invalid IPv4 address: " + ip);
        }
        long existing;
        if (excludePeerId == null) {
            existing = Peer.count("assignedIp = ?1", ip);
        } else {
            existing = Peer.count("assignedIp = ?1 and id <> ?2", ip, excludePeerId);
        }
        if (existing > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("IP " + ip + " is already assigned to another peer")
                            .build());
        }
    }

    /**
     * Build the WireGuard {@code .conf} the client will import.
     *
     * <p>{@code privateKey} may be {@code null} (retention=never reshow). In that
     * case the {@code PrivateKey} line is omitted entirely — the resulting .conf
     * is not directly importable but carries every other parameter, so the user
     * can paste their key in manually or use it as a template.
     */
    private String renderConf(String privateKey, String assignedIp, Settings settings) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Interface]\n");
        if (privateKey != null) {
            sb.append("PrivateKey = ").append(privateKey).append("\n");
        }
        sb.append("Address = ").append(assignedIp).append("/32\n");
        if (settings.wgClientDns != null && !settings.wgClientDns.isBlank()) {
            sb.append("DNS = ").append(settings.wgClientDns).append("\n");
        }
        sb.append("\n[Peer]\n");
        sb.append("PublicKey = ").append(settings.wgServerPublicKey).append("\n");
        sb.append("AllowedIPs = ").append(settings.wgClientAllowedIps).append("\n");
        sb.append("Endpoint = ").append(settings.wgServerEndpoint).append("\n");
        sb.append("PersistentKeepalive = 25\n");
        return sb.toString();
    }
}
