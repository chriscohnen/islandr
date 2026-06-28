# Socket Proxy — JVM-side degraded mode (design)

**Date:** 2026-06-28
**Status:** Approved (design) — implementation pending
**Branch:** `feature/socket-proxy` (off `main` @ 0.9.1, independent sibling of `feature/ironrdp-browser-rdp`)
**Target version:** 0.11.0 (the "v2" line; independent of 0.10.0/ironrdp)
**Implements:** ADR-0012 (Docker via Unix socket proxy), spec.md UC-04 + BR-027..031, arc42 §6.6

## 1. Problem & scope

ADR-0012 decided that production Docker runs an unprivileged islandr container talking to a
host-side `islandr-proxy` over a Unix socket; the container must boot and serve the full
**configuration plane** even when the proxy is absent, and surface that state honestly
("enforcement unavailable") rather than fail or fake success.

This design covers the **JVM side only**. It is fully testable in the JVM/CI against a fake
Unix-socket server — no Go binary, no Docker, no host required.

**In scope (this branch):**
- A `socket` transport mode for the WireGuard and nftables adapters.
- Graceful degradation: proxy absent → "enforcement unavailable", config persists, nothing faked.
- An in-memory enforcement-status surface + REST endpoint + Admin Console banner + diagnostic.
- Reconcile-on-connect: a scheduled probe that does a full re-apply when the proxy appears.
- Container detection as a *fallback default* for the mode + a diagnostic signal.
- The wire **protocol** definition (the contract the Go proxy will implement).
- Full test suite against an in-process fake socket server, traced to BR/UC IDs.

**Out of scope (deferred to a second branch):**
- The Go `islandr-proxy` binary implementing the protocol + systemd `.socket`/`.service` units.
- The production Docker image (`ENV ISLANDR_WG_MODE=socket` etc.) and `install.sh`.

**Explicitly not done now (YAGNI):** the desired-state reconciler refactor (brainstorming
"Approach 2"). The existing `real`/`mock` synchronous paths are left untouched; degraded is only
the exception branch.

## 2. Key decisions

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Approach 1: typed `ProxyUnavailableException` + in-memory status + periodic reconcile | Smallest change, no risk to real/mock paths, honest status, fully fake-socket-testable |
| D2 | Enforcement status is **in-memory**, not a DB table | Intent is already durable (peers, grants). With the proxy down the container cannot read host kernel state, so *everything* is pending by definition; "pending" = "not reconciled since proxy became available". Reconcile = full re-apply (BR-025). No new table. |
| D3 | Mode wiring: image bakes `socket` + container detection as fallback default | "In a container" ≠ "must use proxy"; baking the mode is deterministic, detection is the safety net. Resolution order: explicit config > container default > `mock`. |
| D4 | `ProxyClient` uses pure JDK Unix domain sockets (`java.net.UnixDomainSocketAddress`, Java 16+) | No native lib, no dependency |
| D5 | Mutation API responses stay unchanged; the GUI derives "saved, not yet enforced" from the status endpoint | Minimal API surface |

## 3. Components

New package `de.chriscohnen.islandr.proxy` (shared by `wg` + `firewall`):

| Type | Responsibility |
|------|----------------|
| `ProxyClient` | Send one line-delimited JSON request over the Unix socket, parse the response. Connect failure / timeout → `ProxyUnavailableException`. Pure JDK. |
| `ProxyUnavailableException` | Typed signal that the proxy is unreachable — distinct from operational failures (`WgException`, `NftablesException`). |
| `EnforcementStatus` | `@ApplicationScoped`, in-memory. Fields: `status` (`UNAVAILABLE`/`ACTIVE`/`RECONCILING`), `lastReconcileAt`, `lastProbeAt`, `lastError`. Updated by adapters and the reconciler; read by the REST endpoint. |
| `ContainerDetector` | Detects a container runtime (`/.dockerenv`, `/run/.containerenv`). Used as a mode fallback default and a diagnostic. |

Extended in existing packages:

| Type | Responsibility |
|------|----------------|
| `wg/SocketWgAdapter implements WgAdapter` | `islandr.wg.mode=socket`. Maps each method to a JSON op via `ProxyClient`. `probeServer` returns `null` when the proxy is down (matches the existing contract). |
| `firewall/SocketNftablesAdapter implements NftablesAdapter` | `islandr.nft.mode=socket`. `apply()` writes the ruleset to `islandr.proxy.ruleset-path` then sends `nft_reload`; `validate()` sends `nft_validate`. |
| `wg/WgAdapterProducer`, `firewall/NftablesAdapterProducer` | Add a `socket` branch and the container-detection fallback to mode resolution. |
| `ProxyReconciler` (new, `proxy` package) | `@Scheduled` probe + full re-apply on connect. |

## 4. Wire protocol (the contract for the Go proxy)

Line-delimited JSON over a `SOCK_STREAM` Unix socket. Request `{"op":"...", ...}`,
response `{"ok":true,...}` or `{"ok":false,"error":"..."}`. One request at a time per connection.

| Op | Maps to (host) | Fields | Notes |
|----|----------------|--------|-------|
| `wg_set_peer` | `wg set wg0 peer <pk> allowed-ips <cidr> [preshared-key …]` | `pubkey`, `allowedIps`, `presharedKey?` | iface fixed to `wg0` |
| `wg_remove_peer` | `wg set wg0 peer <pk> remove` | `pubkey` | |
| `wg_show` | `wg show wg0 dump` | — | read-only; backs `showPeers`/`probeServer` |
| `nft_reload` | `nft -f /var/lib/islandr/ruleset.nft` | — | ruleset path is a **server constant**, never a request field |
| `nft_validate` | `nft -c -f /var/lib/islandr/ruleset.nft` | — | read-only dry-run; **new op** added vs ADR-0012's table, needed by `NftablesAdapter.validate()` |
| `nft_flush` | `nft flush ruleset islandr` | — | |

`setIfMtu` is **not** in the allowlist (it would need `ip link`); in `socket` mode MTU changes are
a no-op with a logged warning. (Revisit in branch 2 if needed — out of scope here.)

## 5. Degraded behaviour & call-site changes

When the proxy is unreachable, an enforcing op throws `ProxyUnavailableException`:

- `PeerService.createForUser` / `update` / remove: catch `ProxyUnavailableException` **specifically**
  → set `EnforcementStatus = UNAVAILABLE`, **do not roll back** (the peer stays persisted), continue.
  Any other `WgException` stays fatal (HTTP 500, rollback) exactly as today.
- `RulesetService.recomputeAndApply`: nft `apply()`/`validate()` throwing `ProxyUnavailableException`
  → set `EnforcementStatus = UNAVAILABLE`, leave `FirewallState` unchanged (its `FAILED` state stays
  reserved for a real `nft` rejection), return normally.

Net effect with the proxy down: every mutation succeeds at the DB/API level, nothing is enforced,
status is `UNAVAILABLE`. (BR-027, BR-028, BR-029.)

## 6. Reconcile-on-connect

New `@Scheduled` bean `ProxyReconciler` (interval `islandr.proxy.reconcile-interval`, default 10s):

1. Probe the socket (cheap connect, or `wg_show`).
2. `status == UNAVAILABLE` and probe ok → `RECONCILING` → **full re-apply**:
   - `rulesets.recomputeAndApply()` (nft — already a full ruleset replacement, BR-025);
   - re-push every enabled peer via `wg.setPeer` (a reconcile service method loads enabled peers and
     iterates — no new adapter interface method needed);
   - success → `ACTIVE`, `lastReconcileAt = now`. A real `nft` rejection → `FirewallState = FAILED`
     but `status = ACTIVE` (proxy reachable). Proxy drops mid-reconcile → `UNAVAILABLE`.
3. Probe fails → `UNAVAILABLE`.

Boot: `FirewallBootstrap` runs the same path — proxy up → reconcile; proxy down → `UNAVAILABLE`,
bootstrap is a no-op, no crash (consistent with arc42 §6.4 "start anyway").

## 7. API + GUI

- `GET /api/v1/enforcement/status` → `{ status, lastReconcileAt, lastProbeAt, lastError, runtime }`
  where `runtime` carries the container-detection diagnostic.
- Admin Console: a global banner reads the status and shows, when `status != ACTIVE`, the
  "enforcement unavailable" copy with a link to the install instructions (BR-031). The per-change
  "gespeichert, noch nicht durchgesetzt" hint is derived client-side from the banner state;
  mutation responses are unchanged (D5).
- Settings/Dashboard diagnostic line: "Laufzeit: Container · Enforcement über Socket-Proxy".

## 8. Configuration

| Property | Default | Meaning |
|----------|---------|---------|
| `islandr.wg.mode` / `islandr.nft.mode` | `mock` (unset → container fallback → `mock`) | add `socket` value |
| `islandr.proxy.socket` | `/run/islandr/proxy.sock` | proxy socket path |
| `islandr.proxy.ruleset-path` | `/var/lib/islandr/ruleset.nft` | shared ruleset file for `nft_reload` |
| `islandr.proxy.reconcile-interval` | `10s` | reconcile/probe cadence |

Mode resolution (both producers): explicit config value > (unset && container marker present → `socket`) > `mock`.

## 9. Testing (traced to BR/UC)

- **Unit** — `ProxyClient` against an in-process fake Unix-domain-socket server speaking the protocol:
  ok response; `{"ok":false}` error; server absent → `ProxyUnavailableException`.
  `ContainerDetector` with temporary marker files.
- **Integration** (`@QuarkusTest`, `islandr.wg.mode=socket` / `nft.mode=socket` → fake server bean):
  - proxy absent → `POST /peers` → 201, peer in DB, `GET /enforcement/status` = `UNAVAILABLE`
    (UC-04 steps 1–3; BR-027/028/029);
  - ACL change while degraded → 200, grants persisted, status `UNAVAILABLE` (Gherkin scenario 2);
  - fake proxy comes up → reconciler → `ACTIVE`, server received `nft_reload` + one `wg_set_peer`
    per enabled peer (BR-030; UC-04 step 5; Gherkin scenario 3);
  - degraded never fakes success: the "down" server makes the adapter throw `ProxyUnavailable`,
    asserted distinct from a faked `ok` (BR-029).
- Each test method names its BR/UC ID. London-school for `ProxyClient`/adapters (fake socket),
  Chicago-style for the reconcile integration (real in-memory DB, fake socket).

## 10. Risks & open points

- **R-122** (from ADR-0012) is closed here by the honest-status design: the degraded adapter never
  reports a fake success, and the banner + per-change hint make the gap visible.
- `setIfMtu` no-op in socket mode (see §4) — acceptable for v2 evaluation; revisit if MTU tuning is
  needed inside the container path.
- Minor merge with `feature/ironrdp-browser-rdp` expected only in `settings` (both add fields) — trivial.
- Version `0.11.0` is a proposal; confirm at release time.
