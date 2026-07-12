package de.chriscohnen.islandr.discovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public final class DiscoveryDto {

    private DiscoveryDto() {}

    public record ScanStarted(String jobId) {}

    public record HostView(String ip, List<Integer> openPorts, String typeGuess, boolean alreadyRegistered) {}

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
            List<Integer> ports
    ) {}

    public record ImportRequest(@Valid List<ImportHost> hosts) {}

    public record ImportResult(int imported, int skipped) {}
}
