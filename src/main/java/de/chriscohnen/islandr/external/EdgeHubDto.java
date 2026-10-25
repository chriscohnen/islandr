package de.chriscohnen.islandr.external;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** External-facade DTOs for the edge-hub endpoints (ADR-I-0001/I-0002) —
 *  separate from {@code de.chriscohnen.islandr.acl.EdgeHubDto}, which is
 *  the console-API shape; this one is the stability-committed contract, same
 *  split every other facade area (peers, users, ...) already keeps. */
public final class EdgeHubDto {

    private EdgeHubDto() {}

    /** One peer's preshared key (edge-psk-exposure) — present only for a
     *  peer that actually has one ({@code Peer.presharedKey != null}); a
     *  peer without a PSK configured is simply absent from the list, not
     *  included with a null key. */
    @RegisterForReflection
    public record PeerPsk(String peerId, String peerName, String presharedKey) {}

    @RegisterForReflection
    public record PskResponse(List<PeerPsk> peers) {}

    /** One peer's observed activity, as the edge's own {@code wg show dump}
     *  would report it — same shape {@code WgAdapter.PeerStatus} carries for
     *  the central hub's own interface, so {@code ActivityPoller#applySample}
     *  can treat either source identically (edge-status-report). */
    @RegisterForReflection
    public record PeerSample(
            @NotBlank String publicKey,
            @NotNull Instant lastHandshake,
            String endpoint,
            long rxBytes,
            long txBytes
    ) {}

    @RegisterForReflection
    public record StatusRequest(@Valid List<PeerSample> peers) {}

    /** {@code rejected} names every reported public key that did not belong
     *  to a peer that may reach this edge — drift or a possibly-compromised edge
     *  misreporting, see {@code EdgeExternalResource#status}. Empty in the
     *  ordinary case. */
    @RegisterForReflection
    public record StatusResponse(int accepted, List<String> rejected) {}
}
