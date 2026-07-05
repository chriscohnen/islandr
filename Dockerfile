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

# DB location inside the container. The app default (application.properties) is
# the RELATIVE path `data/islandr.db`, which — with quarkus-micro's WorkingDir of
# `/` — resolves to /data/islandr.db. That dir does not exist in the image, and
# SQLite does not create missing parents, so a bare `docker run` dies at Flyway
# with SQLITE_CANTOPEN (error 14). Pin an ABSOLUTE path under a dir we create
# here (WORKDIR creates /var/lib/islandr, owned by root, and we run as root) and
# declare it a VOLUME, so a plain `docker run` works and data persists. Compose
# overrides this env and mounts a named volume at the same path.
ENV QUARKUS_DATASOURCE_JDBC_URL=jdbc:sqlite:/var/lib/islandr/islandr.db
WORKDIR /var/lib/islandr
VOLUME /var/lib/islandr

COPY dist/${TARGETARCH}/islandr-runner /islandr
EXPOSE 8080
ENTRYPOINT ["/islandr"]
