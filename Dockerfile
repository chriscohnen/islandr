# Demo/dev only — uses mock WireGuard and nftables adapters.
# Production deployments require the native binary under systemd (see docs/adr/0011-process-privilege-model.md).
# Full Docker production support (Unix socket proxy) is planned for v2 (ADR-0012).
#
# NOT `FROM scratch`: the Mandrel native binary is dynamically linked against
# glibc (interpreter /lib*/ld-linux-*.so) and needs libz — scratch has neither,
# so the container exits 127 with no output before the app starts. quarkus-micro
# ships exactly the glibc + zlib + CA-cert runtime a Mandrel binary needs, and
# still runs as root (uid 0) so the /var/lib/islandr volume stays writable.
FROM quay.io/quarkus/quarkus-micro-image:2.0
ARG TARGETARCH
COPY dist/${TARGETARCH}/islandr-runner /islandr
EXPOSE 8080
ENTRYPOINT ["/islandr"]
