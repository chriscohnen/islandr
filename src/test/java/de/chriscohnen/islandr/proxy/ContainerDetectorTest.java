package de.chriscohnen.islandr.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ContainerDetector} (design §3, D3): detects a container
 * runtime via marker files, used as the mode-resolution fallback default and a
 * diagnostic. Driven with temporary marker paths so no real container is needed.
 */
class ContainerDetectorTest {

    /** Bare host: none of the markers exist → not in a container. */
    @Test
    void inContainer_falseWhenNoMarkerPresent(@TempDir Path dir) {
        ContainerDetector detector = new ContainerDetector(List.of(
                dir.resolve(".dockerenv"),
                dir.resolve("run/.containerenv")));

        assertThat(detector.inContainer()).isFalse();
    }

    /** Docker writes {@code /.dockerenv} into the container. */
    @Test
    void inContainer_trueWhenDockerenvPresent(@TempDir Path dir) throws IOException {
        Path dockerenv = dir.resolve(".dockerenv");
        Files.createFile(dockerenv);

        ContainerDetector detector = new ContainerDetector(List.of(
                dockerenv,
                dir.resolve("run/.containerenv")));

        assertThat(detector.inContainer()).isTrue();
    }

    /** Podman/CRI writes {@code /run/.containerenv}. Any single marker suffices. */
    @Test
    void inContainer_trueWhenContainerenvPresent(@TempDir Path dir) throws IOException {
        Path containerenv = dir.resolve("containerenv");
        Files.createFile(containerenv);

        ContainerDetector detector = new ContainerDetector(List.of(
                dir.resolve(".dockerenv"),
                containerenv));

        assertThat(detector.inContainer()).isTrue();
    }
}
