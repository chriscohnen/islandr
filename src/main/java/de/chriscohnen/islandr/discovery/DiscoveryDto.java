package de.chriscohnen.islandr.discovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class DiscoveryDto {

    private DiscoveryDto() {}

    public record ScanStarted(String jobId) {}

    public record HostView(String ip, List<Integer> openPorts, String typeGuess,
                           String hostname, boolean alreadyRegistered,
                           // Issue #76 — on-link only; vendor is derived at
                           // mapping time (DiscoveryResource), never stored.
                           String mac, String vendor) {}

    public record ScanStatus(String state, int total, int done, int found, List<HostView> hosts, String error) {}

    /**
     * One host the admin chose to import. {@code type} must be a real Resource type —
     * not {@code unknown}. {@code ports}, when non-empty, are the discovered open TCP
     * ports to adopt as {@code ResourcePort}s on the created resource.
     */
    public record ImportHost(
            @NotBlank String ip,
            @NotBlank String name,
            @NotBlank
            @Pattern(regexp = "^(computer|router|printer|nas|camera|iot|virt-host|rackserver|kvm|management|other)$",
                    message = "type must be a valid resource type (resolve 'unknown' before importing)")
            String type,
            List<Integer> ports,
            // Optional — DNS label for the resource-name resolver (ADR-0023). The UI
            // pre-fills this from `name` (already space-free for scanned hosts) but
            // an admin can edit or clear it per row before importing. Blank/null =
            // skip. A collision (with an existing resource or another row in the
            // same batch) also silently drops it for that row rather than failing
            // the whole import — see DiscoveryResource#importHosts.
            @Pattern(regexp = "^$|^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$",
                    message = "must be a DNS label (letters, digits, hyphens; not starting/ending with a hyphen)")
            String dnsName,
            // Optional (issue #76) — pre-filled from the scan row's own MAC;
            // the admin can edit/clear it per row before importing, same as dnsName.
            @Size(max = 17)
            String mac
    ) {}

    public record ImportRequest(@Valid List<ImportHost> hosts) {}

    public record ImportResult(int imported, int skipped) {}
}
