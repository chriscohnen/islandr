# Demo/dev only — uses mock WireGuard and nftables adapters.
# Production deployments require the native binary under systemd (see docs/adr/0011-process-privilege-model.md).
# Full Docker production support (Unix socket proxy) is planned for v2 (ADR-0012).
FROM scratch
ARG TARGETARCH
COPY dist/${TARGETARCH}/islandr-runner /islandr
EXPOSE 8080
ENTRYPOINT ["/islandr"]
