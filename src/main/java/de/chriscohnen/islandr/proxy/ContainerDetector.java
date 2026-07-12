package de.chriscohnen.islandr.proxy;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Detects whether the process runs inside a container runtime by probing the
 * marker files runtimes drop into the root filesystem — {@code /.dockerenv}
 * (Docker) and {@code /run/.containerenv} (Podman/CRI).
 *
 * <p>Used two ways (design §3, D3): as the mode-resolution <em>fallback default</em>
 * (unset {@code wg.mode}/{@code nft.mode} + container marker → {@code socket}) and
 * as a diagnostic surfaced on the enforcement-status endpoint. "In a container" is
 * only a default, never an override — an explicit config value always wins.
 */
@ApplicationScoped
public class ContainerDetector {

    private static final List<Path> DEFAULT_MARKERS = List.of(
            Path.of("/.dockerenv"),
            Path.of("/run/.containerenv"));

    private final List<Path> markers;

    public ContainerDetector() {
        this(DEFAULT_MARKERS);
    }

    /** Test seam: inject marker paths so detection can be exercised without a real container. */
    ContainerDetector(List<Path> markers) {
        this.markers = markers;
    }

    public boolean inContainer() {
        return markers.stream().anyMatch(Files::exists);
    }
}
