package de.chriscohnen.islandr.acl;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class EdgeHubDto {

    public record Response(
            String id,
            String name,
            boolean enabled,
            double lat,
            double lng,
            String locationLabel,
            // What a client at this edge dials, and whose key it trusts
            // (ADR-I-0003). Null until the edge's WireGuard side is set up.
            String endpoint,
            String serverPublicKey,
            // edge-peer-dns: resolver for peers at this edge. Null = those
            // peers get no DNS line, see EdgeHub.dnsServerIp.
            String dnsServerIp,
            // False while either of the two above is missing — the admin page
            // uses it to say why this edge cannot take peers yet, instead of
            // leaving the operator to infer it from two empty fields.
            boolean canServePeers,
            // Sites currently assigned to this edge hub — resolved here so the
            // admin page can show "serves 2 sites" without a second round-trip.
            int siteCount,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static Response from(EdgeHub e, int siteCount) {
            return new Response(e.id, e.name, e.enabled, e.lat, e.lng,
                    e.locationLabel, e.endpoint, e.serverPublicKey, e.dnsServerIp, e.canServePeers(),
                    siteCount, e.createdAt, e.updatedAt);
        }
    }

    public record UpsertRequest(
            @NotBlank String name,
            boolean enabled,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            String locationLabel,

            // Optional: an edge is normally created before the machine behind
            // it exists. Blank clears the value again.
            @Size(max = 255) String endpoint,

            // Same 44-char base64 shape PeerDto enforces for a peer's keys —
            // a typo here produces a client config that fails to hand shake
            // with no explanation, so it is caught on the way in.
            @Pattern(regexp = "^$|^[A-Za-z0-9+/]{43}=$",
                    message = "must be a 44-char Base64 WireGuard key")
            String serverPublicKey,

            // edge-peer-dns. Blank/null = no DNS line for peers at this edge,
            // which is the safe default rather than an omission — see
            // EdgeHub.dnsServerIp.
            @de.chriscohnen.islandr.validation.ValidIpAddress
            String dnsServerIp
    ) {}

    private EdgeHubDto() {}
}
