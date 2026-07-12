# ADR-0014 — Device discovery by unprivileged TCP-connect scan of a site's own CIDR (0.12.0)

**Status:** Accepted (implemented in 0.12.0)
**Date:** 2026-07-10 (accepted 2026-07-12)
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0011](0011-process-privilege-model.md) (privilege model this must not widen), [ADR-0005](0005-hub-only-firewall.md) (hub is the only vantage point into a site), [ADR-0006](0006-resource-level-acl.md) (`Resource`/`Site` model this populates), [ADR-0012](0012-docker-socket-proxy.md) (enforcement path this is deliberately independent of), R-052 (the manual-maintenance gap this closes).

## Context

Registering resources is manual, one IP at a time. The trigger for this ADR was concrete: an operator adding **nine cameras** in one site network, typing each IP, name, and port by hand. [R-052](../arc42/11-risks-and-technical-debt.md) already names this — "no auto-discovery means every new server must be manually added" — with a placeholder mitigation (a bulk import API for power-users who can script it). That mitigation helps someone who already has a machine-readable inventory; it does nothing for the operator standing in front of an unknown `/24` asking *which hosts even exist, and what are they?*

The hub is the natural place to answer that. A `Site` carries a `cidr` and an optional `gatewayPeerId` — the site-type peer that routes that CIDR back through WireGuard ([ADR-0006](0006-resource-level-acl.md), [ADR-0005](0005-hub-only-firewall.md)). When that route exists, the hub can reach every host in the site's subnet. So the hub can enumerate live hosts, fingerprint them by open port, and hand the operator a checklist to bulk-create `Resource` rows from — instead of nine round-trips through the resource form.

The tension is that **network scanning is intrusive**, and Islandr's whole security posture is built on *least privilege* ([ADR-0011](0011-process-privilege-model.md)): the process runs as an unprivileged user whose only escalation is `sudo` scoped to exactly `nft` and `wg`. A naive scanner reaches for raw ICMP/ARP sweeps, which need `CAP_NET_RAW` — widening that grant and denting the exact posture ADR-0011 exists to protect. A scan is also a recon primitive: whoever can trigger it can map a remote network. The design has to earn its usefulness without becoming either a privilege escalation or an unbounded port-scanner.

## Decision

**Add an admin-triggered, asynchronous discovery scan that reaches live hosts purely through unprivileged sockets, is bound to a site's own declared CIDR, and produces a reviewable list the admin bulk-imports into `Resource` rows.** Nothing about it widens the ADR-0011 privilege model, and nothing about it touches the enforcement path.

### 1. Discovery mechanism — no privilege, two complementary probes

Host liveness is established with **user-space sockets only** — never a raw socket, never `CAP_NET_RAW`, never a new `sudoers` entry:

- **TCP `connect()`** to a fixed probe-port set — `22, 80, 443, 445, 554, 631, 3389, 5900, 8006, 8080, 8123, 8443, 9100`. `connect` succeeding (**open**) or being refused (**closed**) both prove the host is up and, for an open port, tell us *which* service. A timeout is ambiguous (down, or filtered).
- **UDP port-unreachable liveness** — one UDP datagram to a fixed, likely-closed high port (`40125`) on a **`connect()`-ed `DatagramSocket`**. If the host is up and the port closed, the kernel delivers the ICMP *port unreachable* against that connected socket and Java surfaces it as `java.net.PortUnreachableException` — **without a raw socket**. An exception is a positive liveness signal even when *no* probed TCP port is open; a timeout yields nothing. This is exactly the technique `nmap` falls back to in unprivileged mode, and the reason it works is the reason we can use it: sending UDP and reading the connected-socket error are both unprivileged.

A host counts as **live** if any TCP probe answered (open or refused) **or** the UDP probe raised `PortUnreachableException`. The two probes are complementary: TCP gives liveness *plus* a service fingerprint; the UDP probe adds hosts that expose none of the probed TCP ports but still emit ICMP — partially closing the "silent host" gap (R-140).

### 2. Execution model — ephemeral async job

Scanning a `/24` with connect-timeouts takes seconds to tens of seconds, too long to hold an HTTP request open. A scan is an **in-memory job** (a `ConcurrentHashMap`, ~5-minute TTL, **not persisted**, cleared on restart — no Flyway migration, no job table). Bounded concurrency (e.g. 64 in-flight probes) and a short per-host timeout keep it quick and gentle.

```
POST   /api/v1/sites/{siteId}/discovery/scan        → 202 { jobId }
GET    /api/v1/sites/{siteId}/discovery/scan/{jobId} → { state: running|done|failed,
                                                          progress, hosts: [
                                                            { ip, openPorts[], typeGuess,
                                                              alreadyRegistered } ] }
DELETE /api/v1/sites/{siteId}/discovery/scan/{jobId} → cancel
POST   /api/v1/sites/{siteId}/discovery/import       → [ { ip, name, type, ports[] } ]
```

All four are **admin-only** (`isAdmin=true`, per T-005). `import` is transactional and idempotent on `(site, ip)` — re-importing an already-registered host is a no-op, so a second scan+import never duplicates resources. Results mark `alreadyRegistered` so the UI greys out hosts that are already `Resource` rows.

### 3. Preconditions (HTTP 409, clear German copy)

- **Route to the CIDR.** A scan needs the hub to be able to reach the site's subnet, but a WireGuard tunnel is not the only way that happens. The rule is therefore scoped to what actually needs guarding:
  - A site that **declares a `gatewayPeerId`** (a tunnel route) must have a **recent handshake** on that peer. A promised tunnel that is down is failed fast — *"Der Gateway-Peer dieser Site ist nicht verbunden (kein aktueller Handshake)"* — because the scan would otherwise silently find zero hosts.
  - A site with **no gateway peer** is treated as **directly reachable from the hub** (e.g. the hub's own LAN, or a network on the hub's routing table). The scan is allowed and best-effort: if no route exists it simply finds nothing (R-140), which is a far better default than refusing to let the operator try. *(This corrects the original draft, which 409'd any site without a gateway peer — too strict for a hub-local network.)*
  - In **mock mode** (`islandr.discovery.mode != real`) nothing is actually probed, so the route precondition is skipped entirely. This keeps the feature testable in Docker/dev without WireGuard.
- The site's `cidr` resolves to **≤ 1024 hosts (`/22` or smaller)**. Larger CIDRs are rejected — a `/16` would spawn 65k probe fan-outs (R-142).
- **One active scan per site.** A second `POST` while a job runs **supersedes** it: the running scan is cancelled and replaced, so "scan again" always starts fresh and never dead-ends on a stale job (e.g. one orphaned by a client that navigated away). The one-active-scan invariant — and with it the connect-rate bound — is preserved.

### 4. Guardrails and consent

- **Bound to the site's own declared CIDR.** There is no free-text range input. You can only scan a network you already registered as a `Site` — the tightest honest consent story: *you can only scan what you declared you own.*
- **Admin-only, on explicit click**, never a background poll.
- **Gentle by construction** — bounded concurrency, short per-host timeout, host-count cap, single active scan. It cannot become a connect-flood against the remote LAN (R-142 / T-014).
- **Audited.** Every scan start (`discovery.scan_started`, actor + site + CIDR + host count) and every import (`discovery.import`, actor + site + created IPs) is written to the append-only audit log (§8.4).
- **Transparent copy** in the UI before the scan runs: *"Der Hub baut testweise TCP-Verbindungen zu jeder Adresse in `<cidr>` auf, um erreichbare Geräte zu finden."* No stealth — TCP `connect()` is a full handshake the target logs; that honesty is a feature.

### 5. Service fingerprint → type guess

The probe set serves **two purposes**, and only the first drives the type guess:

- **Fingerprinting ports** identify a `type` (table below).
- **Liveness-only web ports** — `80, 443, 8080, 8123, 8443` — prove reachability but are too generic to name a type on their own (a web UI on 8443 could be anything; 8123 is Home Assistant but there is no such resource type). They establish that the host is up and reachable over HTTP(S); they yield a `computer` guess only as a last resort and otherwise leave `typeGuess = unknown`. `445` is deliberately *not* treated as a NAS on its own, because every Windows host also opens it.

Guesses are pre-filled in the import table and freely editable:

| Open port(s)                     | `typeGuess`  |
|----------------------------------|--------------|
| 554 (RTSP)                       | `camera`     |
| 631 or 9100                      | `printer`    |
| 3389 (RDP)                       | `computer`   |
| 5900 (VNC)                       | `computer`   |
| 22 (SSH)                         | `computer`   |
| 8006 (Proxmox)                   | `rackserver` |
| 445 (SMB) **without** 3389       | `nas`        |
| 445 (SMB) **with** 3389          | `computer`   |
| only liveness-only web ports     | `computer`   |
| none (ICMP-only)                 | `unknown`    |

The `camera` type this leans on (RTSP/554) already exists in the resource-type set (added in `V13`, alongside `rackserver`/`kvm` from 0.11.0) — icon, `typeLabels`, DE/EN i18n, and the backend `@Pattern` allowlist are all in place. Discovery reuses it rather than adding a type; without a camera type the fingerprint for the very use case that triggered this ADR would be useless, so its prior existence is a precondition, not a side effect.

### 6. Mock-first, like `wg`/`nft`

Discovery follows the project's mock-first pattern: a `MockDiscoveryScanner` returns synthetic hosts so the dev laptop and the test suite never touch a real network, and the real socket scanner is selected only in `real` mode. This keeps the feature testable without a live subnet and consistent with how `wg`/`nft` adapters already resolve.

### 7. Independent of the enforcement path

The scan is plain JVM **outbound** TCP/UDP over the kernel's existing WireGuard route. It does **not** go through `nft`, `wg`, or the 0.11.0 socket proxy ([ADR-0012](0012-docker-socket-proxy.md)). Discovery therefore works even in the degraded *"enforcement unavailable"* mode — a scan needs a route, not a working enforcement channel.

## Alternatives considered (Pugh Matrix)

Baseline: **A — unprivileged TCP-connect + UDP-unreachable async scan, bound to the site's CIDR** (the decision).

| Criterion (weight)                                   | A: unprivileged scan (baseline) | B: raw ICMP/ARP scan | C: manual IP paste / CSV import | D: scan-agent on the site gateway |
|------------------------------------------------------|:-------------------------------:|:--------------------:|:-------------------------------:|:---------------------------------:|
| Stays inside the ADR-0011 privilege model (5)        | 0                               | -1                   | 0                               | 0                                 |
| Host-discovery completeness (4)                      | 0                               | +1                   | -1                              | +1                                |
| Bulk-create quick-start value (fingerprint) (4)      | 0                               | 0                    | -1                              | 0                                 |
| Consent / minimal scan surface (3)                   | 0                               | 0                    | +1                              | -1                                |
| No new component to operate (3)                      | 0                               | 0                    | 0                               | -1                                |
| Implementation cost (2)                              | 0                               | -1                   | +1                              | -1                                |
| **Weighted total**                                   | **0**                           | **−3**               | **−3**                          | **−4**                            |

Honest reading:

- **B (raw ICMP/ARP sweep)** discovers more hosts — ARP on-segment finds everything, ICMP is not tied to open ports — but it needs `CAP_NET_RAW`, which means a new capability or `sudoers` grant. That trades away the one thing ADR-0011 exists to protect, for a completeness gain the UDP-unreachable probe already recovers *most* of. Clear reject on the highest-weighted criterion.
- **C (no scan, manual paste / CSV)** is the honest cheap path and is *already* R-052's mitigation. It removes all recon risk because there is no scan — but it does not answer "what exists?" or "what is it?", which is the entire motivation. It ties B at the total but for the opposite reason: safe and useless-for-the-goal rather than capable and over-privileged.
- **D (an agent on the site gateway that scans locally and reports back)** is the most complete (local ARP, no timeouts across the tunnel) and offloads the hub — but it is a whole new component to build, secure, deploy, and update, and it contradicts the thin-site model where the gateway is just a WireGuard peer ([ADR-0005](0005-hub-only-firewall.md)). It also scans from *inside* the LAN with broader reach, weakening the "bound to a declared CIDR" consent story. Worst on cost and operability.
- **E (browser-side scan)** is not in the matrix because it is not possible: a browser cannot raw-scan, cannot open arbitrary TCP sockets, and is blocked by CORS/mixed-content. Rejected outright.

A wins by staying inside the privilege model while still producing a fingerprinted, bulk-importable list. Its real cost — incomplete discovery for fully-filtered hosts — is named as R-140 and mitigated (UDP probe) rather than denied.

## Consequences

**Positive**

- The nine-camera case collapses from nine form round-trips to *scan → tick nine rows → import*, with types pre-guessed from port 554.
- R-052 ("no auto-discovery") is materially addressed for the interactive operator, not just the scripting power-user.
- Zero change to the ADR-0011 privilege model: no raw sockets, no new `sudoers` line, no capability. An RCE gains no scanning power it did not already have as an outbound-socket process.
- Works under degraded enforcement (ADR-0012): discovery depends on a route, not on `nft`/`wg`.
- Mock-first keeps it fully testable without a live subnet.

**Risks created**

- **R-140** — Discovery is *best-effort*. A host that opens none of the probed TCP ports **and** does not emit an ICMP port-unreachable (fully filtered, ICMP-dropping, or ICMP rate-limited — common on hardened devices and behind stateful site routers) is invisible. An operator may read absence-from-results as "device not there." Mitigation: the UDP-unreachable probe recovers the ICMP-emitting subset; the UI states plainly that discovery is best-effort and manual add is always available; the probe-port set is documented so operators know what is looked for.
- **R-141** — The scan is an authenticated **recon / port-scan primitive**. An admin — or an attacker with a stolen admin session or an RCE (R-070) — can enumerate a remote LAN. Mitigation (ties **T-013**): admin-only; bound to a site's *declared* CIDR (no arbitrary ranges); TCP `connect()` is non-stealth and logged by the target; every scan is audited; there is no way to point it at a network the operator has not already registered.
- **R-142** — A large or looping scan could become a **connect-flood** against the remote network. Mitigation (ties **T-014**): host-count cap (`/22`, ≤1024 hosts), bounded concurrency, short per-host timeout, and one active scan per site.

**Accepted trade-offs**

- Discovery will never be as complete as a privileged raw scan. Accepted deliberately as the price of not widening ADR-0011 — the UDP probe narrows, but does not close, the gap.
- Jobs are in-memory and ephemeral: a hub restart mid-scan loses the job and the operator re-runs it. Accepted — a scan is cheap to repeat and not worth a persistence layer.
- The probe-port set is fixed in v1 (no operator-tunable ports). Accepted; a configurable set is a later refinement, not a launch blocker.

## Follow-ups (traceability per the docs contract)

These fired when the ADR moved from Proposed to Accepted (0.12.0) — deferred until then so the live risk/threat registers did not carry entries for an unbuilt feature. All are now in place:

- ✅ **R-140, R-141, R-142** added to [arc42 §11](../arc42/11-risks-and-technical-debt.md); **R-052** updated to note interactive discovery now exists (not only a scripting import), with R-140 as its residual.
- ✅ **T-013** (recon / port-scan primitive) and **T-014** (connect-flood DoS on a remote network) added to the [§8.1 STRIDE threat model](../arc42/08-crosscutting-concepts.md), each cross-referenced from its mitigation in §8.2 and from R-141 / R-142.
- ✅ Discovery captured across the spec: **F-21** in [prd.md](../prd.md); **UC-05** (error paths) and Business Rules **BR-032…BR-037** with a Gherkin feature in [spec.md](../spec.md); a **§6.7 runtime scenario** and the `discovery` building block (§5.3) in arc42.
- ✅ Status set to **Accepted** here and in the [ADR index](README.md) / [arc42 §9](../arc42/09-architecture-decisions.md).

## References

- [ADR-0011](0011-process-privilege-model.md) — the privilege model this must not widen (why TCP-connect + unprivileged UDP, not raw ICMP/ARP).
- [ADR-0005](0005-hub-only-firewall.md) — the hub as the only vantage point into a site; the thin-site model (why not a scan-agent).
- [ADR-0006](0006-resource-level-acl.md) — the `Site`/`Resource`/`gatewayPeerId` model discovery populates.
- [ADR-0012](0012-docker-socket-proxy.md) — the enforcement path this is deliberately independent of.
- [R-052](../arc42/11-risks-and-technical-debt.md) — the manual-maintenance gap this closes.
- `java.net.PortUnreachableException` / `DatagramSocket.connect` — the unprivileged connected-UDP-socket ICMP-unreachable mechanism.
- Prior art: `nmap` unprivileged host discovery (`connect()` TCP scan + connected-UDP `ECONNREFUSED` fallback).
