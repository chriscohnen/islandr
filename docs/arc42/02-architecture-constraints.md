# 2. Architecture Constraints

## 2.1 Technical Constraints

| ID | Constraint | Source |
|---|---|---|
| TC-1 | Backend runs on Linux (Ubuntu/Debian 22.04+). WireGuard and nftables are Linux kernel features. No macOS or Windows production deployment. | PRD N-02 |
| TC-2 | Production artifact is a single native binary (Quarkus + GraalVM Mandrel). No JVM install required on the hub VM. | PRD N-03, [ADR-0001](../adr/0001-quarkus-backend.md) |
| TC-3 | No external service dependencies at runtime. No SaaS, no cloud API, no external database. The binary runs air-gapped if needed. | PRD N-01 |
| TC-4 | TLS termination is handled by the reverse proxy (Caddy, nginx, or Traefik), not by Islandr itself. Islandr listens on plain HTTP internally. | PRD N-04 |
| TC-5 | Persistence is SQLite by default. PostgreSQL is supported via env vars. One set of Flyway migrations covers both dialects. | [ADR-0004](../adr/0004-sqlite-dev-postgres-prod.md) |
| TC-6 | Frontend uses Vue.js loaded via ESM import maps (no npm, no bundler in dev). Production ships esbuild-bundled static files served by the Quarkus binary. | [ADR-0002](../adr/0002-vue-without-npm.md) |
| TC-7 | Firewall enforcement uses nftables exclusively. ufw is not used. Rules are generated programmatically and validated with `nft -c -f` before every atomic reload. | [ADR-0003](../adr/0003-nftables-replaces-ufw.md) |
| TC-8 | The hub VM holds no UCG credentials and initiates no connections into the trusted internal network. | [ADR-0005](../adr/0005-hub-only-firewall.md) |
| TC-9 | Islandr runs as an unprivileged system user (`islandr`). Privilege for `nft` and `wg` commands is granted via scoped `sudoers` entries or `CAP_NET_ADMIN` capability. | [ADR-0011](../adr/0011-process-privilege-model.md) |
| TC-10 | Java 21 (LTS). Source and target compatibility set to Java 21 in `build.gradle.kts`. | `build.gradle.kts` |

## 2.2 Organizational Constraints

| ID | Constraint | Source |
|---|---|---|
| OC-1 | License is EUPL-1.2 — EU-governed copyleft, compatible with AGPL, permits commercial use. | [ADR-0009](../adr/0009-license-eupl-1.2.md) |
| OC-2 | UI is bilingual DE/EN with a runtime language toggle. German default, informal _Du_. Status is always icon + text label, never color alone. No emoji in UI or copy. | PRD N-08 |
| OC-2a | Light and dark theme are equal-weight and ship together in v1. Sysadmins often work in dark mode; both themes are first-class, not an afterthought. | PRD N-09 |
| OC-2b | Both themes must meet WCAG AA: body text contrast ≥ 4.5:1, large text ≥ 3:1, visible `:focus-visible` ring on all interactive elements. | PRD N-10 |
| OC-3 | Fonts and icons are self-hosted. No CDN calls at runtime (IBM Plex Sans/Mono, Lucide-style SVG icons). | [ADR-0010](../adr/0010-font-and-icon-asset-self-hosting.md) |
| OC-4 | One developer team. Architecture decisions must minimize operational overhead for a solo self-hoster. | PRD personas |

## 2.3 Conventions

| ID | Convention | Detail |
|---|---|---|
| CV-1 | All REST endpoints under `/api/v1/`. | Stable prefix for scripting and future agents. |
| CV-2 | Conventional Commits for git messages. Issue numbers in commit bodies. | Contributor guide |
| CV-3 | Flyway migration scripts named `V{n}__{description}.sql`. One script per schema change. | `src/main/resources/db/migration/` |
| CV-4 | Free-text fields interpolated into nftables rulesets (e.g. comment fields) are sanitized. Structural parameters (IPs, ports) are typed and DB-constrained. | PRD N-12 |
