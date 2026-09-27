# 5. Building Block View

## 5.1 Level 1 — System Context

See [Chapter 3](03-system-scope-and-context.md). The C4 Level 1 diagram is there.

## 5.2 Level 2 — Containers

### C4 Level 2 — Container View

![Container Diagram](../../architecture/diagrams/structurizr-Containers.png)

### Container descriptions

| Container | Technology | Responsibility | Source |
|---|---|---|---|
| **Admin Console** | Vue.js (ESM, no npm) | Data-dense SPA for admins. Peers, ACL matrix (Roles × Resources), users, roles, audit log, dashboard, runtime settings. | `src/main/resources/META-INF/resources/js/` |
| **Self-Service Portal** | Vue.js (ESM, no npm) | Guided SPA for end users. Enroll devices (3-step flow), view access list in plain language. German UI, informal _Du_. | `src/main/resources/META-INF/resources/js/views/MyAccessView.js` |
| **Islandr Backend** | Quarkus 3 / Java 21 | REST API under `/api/v1/`, domain logic, WireGuard and nftables adapters, OIDC verification, activity polling, audit logging. Ships as a GraalVM native binary. | `src/main/java/de/chriscohnen/islandr/` |
| **Database** | SQLite (default) or PostgreSQL | Persistent storage for all domain entities, activity samples, audit log, and runtime settings. Schema managed by Flyway. | `src/main/resources/db/migration/` |

## 5.3 Level 3 — Backend Components

### C4 Level 3 — Backend Components

![Component Diagram](../../architecture/diagrams/structurizr-Components.png)

### Component descriptions

| Package | Responsibility | Key Classes |
|---|---|---|
| `auth` | Session management, local admin login, OIDC Authorization Code Flow callback, admin bootstrap on first start, session filter on every request. Progressive login throttle counted per account *and* per source address, never a lockout. WebAuthn ceremonies for the recovery admin ([ADR-0028](../adr/0028-webauthn-library-and-integration.md)) — offered alongside the password on the login screen, registered and managed from Settings. | `SessionFilter`, `AuthResource`, `OidcAuthResource`, `AdminBootstrap`, `Session`, `Auth`, `LoginThrottle`, `WebAuthnResource`, `WebAuthnService`, `RelyingPartyId` |
| `identity` | OIDC provider registry (runtime-configurable, stored in DB), JWKS cache with TTL, ID token verification (signature, expiry, audience). | `OidcLoginService`, `IdTokenVerifier`, `JwksCache`, `OidcProvider` |
| `peer` | Full peer lifecycle (create, enable, disable, delete). Keypair generation. QR code rendering via ZXing. Activity poller (`@Scheduled`, 30s). Self-service peer creation under `/api/v1/me/peers`. | `PeerService`, `PeerResource`, `MyPeerResource`, `UserPeerResource`, `ActivityPoller`, `QrService` |
| `acl` | Sites, Resources, ResourcePorts, Roles, RoleResourceGrants. ACL matrix API. Port group management. My-access resolution for end users. | `SiteService`, `ResourceService`, `RoleService`, `AclMatrixResource`, `MyAccessResource` |
| `firewall` | Full ruleset computation from ACL model. nftables rule string building. `nft -c -f` validation + `nft -f` atomic reload. Real / Mock / DryRun adapter, selected by `islandr.nft.mode`. A boot-time table keeps the hub closed until Islandr is enforcing ([ADR-0031](../adr/0031-fail-closed-boot-ruleset.md)). | `RulesetService`, `RuleBuilder`, `FirewallResource`, `FirewallBootstrap`, `RealNftablesAdapter`, `MockNftablesAdapter` |
| `wg` | WireGuard CLI adapter. `wg set`/`wg show` operations. Real / Mock / DryRun implementations, selected by `islandr.wg.mode`. | `WgAdapter`, `RealWgAdapter`, `MockWgAdapter`, `WgAdapterProducer` |
| `user` | User CRUD, system role management (ADMIN / END_USER), avatar resolution chain (Gravatar → MS365 photo → deterministic initials). Withdrawing a user's peer access lives in a service, not in the REST resource, so the console and the external facade share one implementation. | `UserResource`, `UserAccessService`, `AvatarService`, `UserAvatarResource` |
| `audit` | Immutable append-only audit log. Written by every package on every mutating action. Read via `GET /api/v1/audit`. | `AuditService`, `AuditResource`, `AuditLog` |
| `settings` | Runtime instance settings table (WG interface config, subnet, server public key, endpoint, private-key retention mode, OIDC providers, trusted reverse proxies). Edited via Admin Console without restart. | `SettingsService`, `SettingsResource`, `Settings` |
| `dashboard` | Aggregation endpoint: online peer count, firewall last-reload timestamp, audit summary. | `DashboardResource`, `DashboardDto` |
| `proxy` | Host-side socket-proxy channel for `wg`/`nft` when the backend runs unprivileged in a container. Adapter-mode resolution (explicit > container-detected > mock), degraded "enforcement unavailable" state and its status endpoint ([ADR-0012](../adr/0012-docker-socket-proxy.md)). | `ProxyClient`, `ProxyReconciler`, `AdapterMode`, `ContainerDetector`, `EnforcementResource`, `EnforcementStatus` |
| `discovery` | Admin-triggered device discovery: enumerate a site's own CIDR, probe host liveness with unprivileged sockets only, fingerprint a resource type from open ports, and bulk-import the reviewed selection as `Resource` rows. Scan jobs are in-memory and ephemeral ([ADR-0014](../adr/0014-device-discovery.md)). | `DiscoveryResource`, `DiscoveryJobs`, `DiscoveryScanner`, `HostProbe`, `CidrHosts`, `TypeFingerprint`, `DiscoveryDto` |

| `dns` | Minimal authoritative resolver on UDP/TCP 53, opt-in in Settings. Answers the managed resource zone with an ACL check (NXDOMAIN where there is no grant), answers `hub.<zone>` and an optional alias **without** one — the ruleset filters forwarded traffic, not traffic to the hub — and forwards everything else upstream verbatim ([ADR-0023](../adr/0023-dns-resolver.md)). | `DnsResolverService`, `DnsQueryHandler`, `DnsMessage` |
| `tls` | Keystore management with runtime hot-swap, no restart ([ADR-0015](../adr/0015-tls-certificate-management.md)). Issues a self-signed certificate for the hub names where no public CA can, and exposes its SHA-256 fingerprint for a one-time comparison. | `TlsKeyStoreProvider`, `HubCertificateService`, `SelfSignedCert` |
| `acme` | Hand-rolled RFC 8555 client: order, HTTP-01 / manual DNS-01 validation, and scheduled renewal when ACME is enabled. No certificate library ([ADR-0019](../adr/0019-acme-client.md), [ADR-0020](../adr/0020-acme-dns01.md)). | `AcmeService`, `AcmeChallengeResource`, `Csr`, `Der`, `Jws` |
| `admin` | Instance config export/import with seeded-data timestamp repair, and the version/update-check endpoint that polls the latest GitHub release. | `AdminResource`, `VersionResource`, `ConfigExportService` |
| `external` | The machine-facing facade under `/api/external/v1` — peers, users, roles, sites, resources, effective grants, audit log. Deliberately separate from the console API: versioned, API-key authenticated, and switchable off as a whole ([ADR-0026](../adr/0026-external-api-facade.md)). Read-mostly; the only mutations are creating a peer and disabling a user or a peer. | `PeerExternalResource`, `UserExternalResource`, `GrantExternalResource`, `AuditExternalResource`, `ExternalApiToggleFilter` |
| `apikey` | Keys for that facade: issue, hash, revoke, last-used tracking, and the filter that authenticates every facade request. A key is shown once at creation and stored hashed. | `ApiKeyService`, `ApiKeyResource`, `ApiKeyAuthFilter`, `ApiKey` |
| `webhook` | Outbound event notifications: subscriptions, per-event dispatch with an HMAC signature, and a delivery record per attempt. Dispatch never blocks the action that produced the event — the receiver belongs to someone else. | `WebhookService`, `WebhookDispatcher`, `WebhookDeliveryRecorder`, `Webhook` |
| `hosthealth` | Periodic CPU, memory and swap sampling of the hub itself for the dashboard load card. Real or mock, selected by `islandr.host-health.mode`. | `HostHealthSampler`, `HostHealthDto` |

Three packages are deliberately **not** modelled as building blocks: `crypto`, `network` and `validation` carry no responsibility of their own — they are libraries the packages above call (key material, CIDR arithmetic, shared constraint annotations) and appear in no runtime scenario on their own.

### Interface contracts

Each package exposes its public surface via JAX-RS resource classes. No cross-package service-to-service REST call exists — all cross-package communication is direct CDI injection. The only packages that communicate with external systems are `wg` (WireGuard CLI), `firewall` (nftables CLI), `identity` (OIDC provider HTTPS), `proxy` (host socket proxy over a Unix socket), `discovery` (outbound TCP/UDP sockets into a site subnet over the existing WireGuard route), `acme` (the ACME CA over HTTPS), `dns` (an upstream resolver over UDP/TCP 53), `admin` (the GitHub releases endpoint for the update check), and `webhook` (a subscriber endpoint over HTTPS).

| Package | Public interface | Source location |
|---|---|---|
| `proxy` | `GET /api/v1/enforcement/status`; internally `ProxyClient` speaks a line-based JSON protocol over `/run/islandr/proxy.sock` | `src/main/java/de/chriscohnen/islandr/proxy/` |
| `discovery` | `POST /api/v1/sites/{siteId}/discovery/scan` → `202 {jobId}`; `GET …/scan/{jobId}` → job state + hosts; `DELETE …/scan/{jobId}` → cancel; `POST …/discovery/import` → created/skipped counts. All admin-only (T-005). | `src/main/java/de/chriscohnen/islandr/discovery/` |

| `external` | `/api/external/v1/{peers,users,roles,sites,resources,grants,audit}` — `GET` throughout; `POST /peers`, `PUT /users/{id}/enabled`, `PUT /peers/{id}/enabled`. API-key authenticated, and off entirely unless the facade is enabled. | `src/main/java/de/chriscohnen/islandr/external/` |
| `apikey` | `GET/POST/DELETE /api/v1/apikeys` (admin-only); internally `ApiKeyAuthFilter` authenticates every `/api/external/v1` request. | `src/main/java/de/chriscohnen/islandr/apikey/` |
| `webhook` | `GET/POST/PUT/DELETE /api/v1/webhooks` plus delivery history (admin-only); outbound `POST` to the subscriber URL with an HMAC signature header. | `src/main/java/de/chriscohnen/islandr/webhook/` |
| `auth` | `/api/v1/auth/…` for local and OIDC login; `/api/v1/auth/webauthn/{availability,register/challenge,register/verify,login/challenge,login/verify}` plus credential list and delete. The relying-party id comes from the request's `Host` header and is `null` for an IP literal, which is what `availability` reports. | `src/main/java/de/chriscohnen/islandr/auth/` |
| `dns` | UDP and best-effort TCP on port 53, bound to the tunnel address only. Authoritative for the managed zone and for `hub.<zone>`; everything else forwarded upstream unparsed. | `src/main/java/de/chriscohnen/islandr/dns/` |
| `hosthealth` | No HTTP surface of its own — `dashboard` reads the latest sample. Sampling runs on a schedule and is mock-able via `islandr.host-health.mode`. | `src/main/java/de/chriscohnen/islandr/hosthealth/` |

### Firewall trigger points

The following events trigger a full ruleset recompute and atomic reload:

| Event | Package | Method |
|---|---|---|
| Peer created | `peer` | `PeerService.createPeer()` |
| Peer enabled / disabled | `peer` | `PeerService.setPeerEnabled()` |
| Peer deleted | `peer` | `PeerService.deletePeer()` |
| Role membership changed | `acl` | `RoleService.setMembers()` |
| Role grant changed | `acl` | `RoleService.setGrants()` |
| Resource added / updated / deleted | `acl` | `ResourceService.*` |
| ResourcePort added / updated / deleted | `acl` | `ResourceService.*` |
| Site CIDR changed | `acl` | `SiteService.*` |
