# Contributing to Islandr

## Bug reports and ideas — yes please

Open a [GitHub Issue](https://github.com/chriscohnen/islandr/issues) for:

- Bug reports (include steps to reproduce, OS, Java version if on JVM mode)
- Feature ideas or suggestions
- Questions about setup or behaviour

## Pull requests — not accepted

Islandr is a solo project. I use agentic coding to iterate fast and I maintain full control over architecture and code style. Pull requests will be closed without merge, no matter how good the code is. This is not a reflection on your contribution — it's a deliberate project boundary.

If you find a bug and want to share a fix, post it as an Issue with the patch as a code block. I'll apply it myself if it fits.

## Forks

EUPL-1.2 is a copyleft licence — you are free to fork, modify, and distribute under the same licence. If you build something significant on top of Islandr, I'd love to hear about it in an Issue.

## Running it locally

Dev server (Quarkus live coding):

```bash
./gradlew quarkusDev
# → http://localhost:8080
```

The `%dev` profile ships with `islandr.admin.user=admin` / `islandr.admin.password=admin` so the local login just works. **In prod the password has no default** — operators must set `ISLANDR_ADMIN_PASSWORD` as an env var, otherwise `/api/v1/auth/login` returns HTTP 503 ("local admin login disabled"). This is deliberate: a known default in containers is a security hole; a loud failure is not.

Tests (1000+, runs in ~25 s after warm start):

```bash
./gradlew test
```

The test profile uses an in-memory SQLite that's wiped per run (`clean-at-start=true`) and a `MockWgAdapter` so no `wg` binary is needed.

## Repository layout

```
islandr/
├── README.md                                # this file
├── CLAUDE.md                                # guidance for Claude Code
├── build.gradle.kts                         # Gradle 9.1 / Kotlin DSL
├── docs/
│   ├── prd.md                               # Product Requirements Document
│   ├── install.md                           # Installation guide (native binary, Docker)
│   ├── install/                             # setup-hub.sh, reverse-proxy.md, hardening.md, identity-microsoft365.md, fail2ban.md
│   ├── faq.md                               # Operational FAQ (logs, wg/nft troubleshooting)
│   ├── arc42/                               # Architecture documentation (arc42, 12 chapters)
│   └── adr/                                 # Architecture Decision Records (Nygard + Pugh)
│       └── README.md                        # ADR index — one file per decision, numbered 0001+
├── architecture/
│   ├── workspace.dsl                        # C4 model (Structurizr DSL) — source of diagrams
│   ├── docs/                                # Markdown pages rendered into the interactive C4 site
│   │   ├── 01-overview.md                   # home / entry point of the architecture portal
│   │   └── 02-roadmap.md                    # roadmap page
│   └── diagrams/                            # generated C4 PNGs + .puml, embedded in arc42
├── scripts/
│   ├── update.sh                            # download, verify, swap the binary, roll back if it fails
│   ├── backup.sh                            # gzip-compressed, rotated SQLite backup via `sqlite3 .backup`
│   └── check-templates.mjs                  # compiles every inline Vue template in CI (no npm, uses the vendored Vue)
├── src/
│   ├── main/java/de/chriscohnen/islandr/
│   │   ├── acl/         # RBAC0: Roles, Resources, Ports/PortGroups, Sites, ACL matrix, "Mein Zugang", port reservations (#72)
│   │   ├── acme/        # hand-rolled RFC 8555 ACME client — Let's Encrypt auto-provisioning
│   │   ├── admin/       # config export/import, version check
│   │   ├── apikey/      # admin-issued API keys for the external automation API (ADR-0026)
│   │   ├── audit/       # audit log (entity, diff, resource, service)
│   │   ├── auth/        # Session, SessionFilter, AdminBootstrap, AuthResource, OidcAuthResource
│   │   ├── crypto/      # EncryptionService — AES-256-GCM for secrets/keys at rest
│   │   ├── dashboard/   # dashboard aggregation (DTO + resource)
│   │   ├── discovery/   # unprivileged CIDR scan for device discovery (ADR-0014), the link-scope gate that skips mDNS/LLMNR for off-link targets, and MAC/OUI vendor lookup (#76)
│   │   ├── dns/         # hand-rolled DNS wire format: peer-facing resource-name resolver (ADR-0023), plus PTR/mDNS/LLMNR/NetBIOS/SSDP lookups feeding discovery's hostname and MAC suggestions (#45, #48, #76)
│   │   ├── external/    # /api/external/v1 facade: API-key auth, peers/users/sites/resources/roles (ADR-0026)
│   │   ├── firewall/    # nftables RuleBuilder + adapters (real/mock/dry-run) + RulesetService
│   │   ├── hosthealth/  # hub CPU/memory/swap sampler, hand-rolled from /proc (issue #73)
│   │   ├── identity/    # OidcProvider + OidcCustomProvider (issue #69), JwksCache, IdTokenVerifier, OidcLoginService, AvatarFetcher
│   │   ├── network/     # network diagnostics: ping/tracepath/mtr over an unprivileged shell (ADR-0025)
│   │   ├── peer/        # Peer entity + DTO + Resource + Service + IpSubnet + QrService
│   │   ├── proxy/       # Docker socket-proxy client + reconciler (ADR-0012)
│   │   ├── settings/    # singleton settings (WG topology, retention mode, hub geocoding)
│   │   ├── tls/         # built-in TLS termination, cert hot-swap (ADR-0015)
│   │   ├── user/        # User + Resource + AvatarService + Google Workspace import + access expiry (#53)
│   │   ├── validation/  # @ValidIpAddress / @ValidCidr custom validators
│   │   ├── webhook/     # outbound event webhooks (issue #68)
│   │   ├── wg/          # WgAdapter (real shells out, mock for dev/CI)
│   │   └── NativeReflectionConfig.java      # GraalVM native-image reflection registration
│   ├── main/resources/
│   │   ├── application.properties
│   │   ├── data/oui-vendors.csv             # bundled IEEE MA-L registry — MAC prefix → vendor, resolved offline (#76)
│   │   ├── db/migration/                    # Flyway migrations V1–V76, portable SQL
│   │   └── META-INF/resources/              # static frontend assets
│   │       ├── index.html                   # importmap, single page
│   │       ├── favicon.svg                  # cyan island + waves
│   │       ├── api/openapi.yml              # hand-written OpenAPI spec for the external API facade (ADR-0026)
│   │       ├── css/                         # tokens.css + components.css + app.css
│   │       └── js/                          # Vue 3 modules, no build
│   └── test/                                # 903 tests, JUnit 5 + RestAssured + AssertJ
```
