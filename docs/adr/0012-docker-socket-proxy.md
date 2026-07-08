# ADR-0012 — Docker deployment via Unix socket proxy (v1 line, 0.11.0)

**Status:** Accepted
**Date:** 2026-06-06 (proposed) · 2026-06-28 (accepted)
**Deciders:** Christian Cohnen
**Target release:** 0.11.0 — pulled forward into the v1 line (production Docker is v1-core, not a later usability tier).

## Context

The early Islandr Docker image ships for demo and evaluation only: it uses the mock WireGuard and nftables adapters and cannot manage real peers or firewall rules, so production deployments require the native binary under systemd (ADR-0011). Making Docker production-capable is v1-core work, targeted for 0.11.0 — pulled forward from the originally planned v2 usability line.

The reason: `nft` and `wg` run on the host kernel, not inside the container. To call them from a container, Docker would need `--cap-add NET_ADMIN` and `--network host`. With `--network host` that capability spans all network namespaces visible to the host — a compromised islandr process could manipulate any nftables table and any network interface on the machine. This violates ADR-0011's principle of least privilege.

Other escape hatches are worse: the Docker socket (`/var/run/docker.sock`) allows spawning privileged containers and is a full host-escape vector. `--pid=host` + `nsenter` is equivalent to root.

There is no safe way to call host-privileged tools from a container without a mediating process that holds the privilege on behalf of the container.

## Decision

In 0.11.0 (v1 line), a **Unix socket proxy** (`islandr-proxy`) runs as a systemd service on the host alongside the container. The container mounts only the proxy socket — no capabilities, no host PID namespace, no Docker socket.

```
Container                          Host
──────────────────────────────────────────────────────────────
islandr-binary
  │
  │  JSON over Unix socket:
  │  {"op":"nft_reload"}            (ruleset path is a server constant)
  │  {"op":"wg_set_peer","interface":"wg0","pubkey":"...","allowedIps":"..."}
  │
  └── /run/islandr/proxy.sock ────► islandr-proxy.service (host)
                                          │
                                          │  validates: op in allowlist
                                          │  validates: path is under /var/lib/islandr/
                                          │  validates: interface == wg0
                                          │
                                          ├── sudo nft -f /var/lib/islandr/ruleset.nft
                                          └── sudo wg set wg0 peer <pubkey> allowed-ips <cidr>
```

### What the proxy allows (complete allowlist)

| Op               | Command                                                                    | Constraints                                                                                                                     |
| ---------------- | -------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `wg_set_peer`    | `sudo wg set wg0 peer <pubkey> allowed-ips <cidr>[ preshared-key <file>]`  | interface fixed to `wg0`; pubkey & preshared key = base64 of exactly 32 bytes; every `allowed-ips` element validated as a CIDR; the PSK is written to a short-lived `0600` file and passed by path, never on the command line |
| `wg_remove_peer` | `sudo wg set wg0 peer <pubkey> remove`                                     | pubkey validated (base64/32 bytes)                                                                                             |
| `wg_show`        | `sudo wg show wg0 dump`                                                     | read-only; stdout returned in the `dump` field                                                                                |
| `nft_validate`   | `sudo nft -c -f /var/lib/islandr/ruleset.nft`                              | ruleset path is a server-side constant, not a request field                                                                   |
| `nft_reload`     | `sudo nft -f /var/lib/islandr/ruleset.nft`                                 | ruleset path is a server-side constant, not a request field                                                                   |

Anything outside this list is rejected with an error — the proxy has no shell, no exec, no wildcard.

> This table matches the ops actually sent by `ProxyClient` and implemented in
> `islandr-proxy/`. An earlier draft listed `nft_flush` (`nft flush ruleset islandr`);
> the JVM never emits it — it clears rules by reloading a validated ruleset via
> `nft_validate` + `nft_reload` — so it is not a proxy op. `wg_set_peer` carries an
> optional `presharedKey`, and `nft_validate` (dry-run `nft -c -f`) was added.

### Container run (0.11.0)

```bash
docker run \
  -v /run/islandr/proxy.sock:/run/islandr/proxy.sock \
  -v /var/lib/islandr:/var/lib/islandr \
  -p 8080:8080 \
  ghcr.io/chriscohnen/islandr:latest
```

No `--cap-add`, no `--network host`, no `--privileged`.

### Configuration plane vs enforcement plane (running without the proxy)

islandr separates two planes. The **configuration plane** — GUI, peers, users, groups, ACLs, settings, audit, JSON export/import — runs unconditionally inside the container and persists to its volume. The **enforcement plane** — pushing the computed ruleset to `nft` and peers to `wg` on the host kernel — is the only part that needs privilege, and privilege cannot be bootstrapped from an unprivileged container (the premise of this ADR and of ADR-0011).

So the container **always boots, with or without the proxy**. The socket-client `WgAdapter`/`NftAdapter` probe `/run/islandr/proxy.sock` at startup and per operation; when it is absent or unreachable, islandr runs in a degraded **"enforcement unavailable"** state instead of failing. A bare `docker run` — volume and port, no socket mount — therefore gives the full GUI to explore, evaluate, and build a complete configuration:

```bash
docker run -v islandr-data:/var/lib/islandr -p 8080:8080 ghcr.io/chriscohnen/islandr:latest
```

The GUI surfaces proxy availability honestly: a persistent banner ("Socket-Proxy nicht verfügbar — Konfiguration wird gespeichert, aber nicht durchgesetzt") linking to the install instructions, plus per-change feedback ("gespeichert, noch nicht durchgesetzt"). Changes made while degraded are persisted and marked pending; once a proxy connects, islandr reconciles and applies the pending state. The published image runs the **real** socket-client adapter in this degraded mode — not the dev/CI mock, which fakes success and would mislead.

From there, two graduation paths, both maximally convenient:

1. **Stay in Docker** — run `install.sh` on the host to add the socket proxy (`islandr` user, scoped sudoers, `wg0`, `islandr-proxy` + systemd units), mount its socket into the *same* container, and the existing configuration starts enforcing. No rebuild; the GUI banner links exactly this path.
2. **Move to native** — export the full config as JSON (`GET /api/v1/admin/config/export`) and import it into a native islandr install (ADR-0011) that already holds privilege.

A privileged **sidecar** proxy container (`--network host` + `CAP_NET_ADMIN`, the app still unprivileged) was considered to approach a pure `docker compose up`. It is rejected: a compromised sidecar holds raw `CAP_NET_ADMIN` in the host network namespace, so the allowlist no longer bounds it, whereas a compromised host daemon is still constrained by the scoped sudoers rules. The host daemon trades some deployment convenience for stronger worst-case containment (see the Pugh matrix).

### Protocol

Line-delimited JSON over a Unix domain socket (SOCK_STREAM). Request: `{"op":"...", ...fields}`. Response: `{"ok":true}` or `{"ok":false,"error":"..."}`. Synchronous — the proxy processes one request at a time per connection.

### islandr-proxy implementation

A small **Go** binary (< 300 lines), statically compiled, shipped in the same release artifacts as the islandr native binary and installed alongside it. The implementation must execute every host command as an **argument vector** (no shell string), so the validation/allowlist is the only path to `nft`/`wg`; this is why a `socat` + shell handler is unfit for the shipped version — a quoting bug at the `sudo` call is a command-injection hole. A Python-stdlib single-file script (`subprocess` with arg lists, `json`, `re`/`ipaddress`) is an acceptable prototype; `socat` + bash is fine only for a throwaway spike. The proxy runs as the `islandr` user with the scoped sudoers rules of ADR-0011, and takes its listening socket from **systemd socket activation** (`RuntimeDirectory=islandr`, `SocketMode=0600`, `SocketUser`/`SocketGroup=islandr`), which establishes the R-120 ownership and mode without custom code.

**Why Go — trust over raw binary size.** At a privilege boundary, "small" means small *trusted computing base* and *auditable source*, not smallest file. Three options were weighed: a Python stdlib script (most readable source, but the trusted base is the whole CPython interpreter on the host, and a plaintext script at a `sudo` boundary can be rewritten by a compromised `islandr` process and inherit its rights); a Rust binary (smallest binary and runtime, but only if it stays `std`-only — idiomatic JSON via `serde`/`tokio` pulls a crate tree that becomes the supply-chain surface to vet); and Go. Go wins because its standard library covers everything the proxy needs (`net`, `os/exec`, `encoding/json`, `regexp`), so `go.mod` carries **zero third-party dependencies** — there is nothing external to audit, only the ~280 lines plus the well-known stdlib. The 2–5 MB runtime is the price for that, and it is a single immutable artifact that can be reproducibly built and signed. Python stays the prototype language only; it is never the shipped boundary. Four rules hold regardless of language: (1) zero third-party dependencies; (2) reproducible build (`-trimpath`, `CGO_ENABLED=0`) plus a signed artifact and published checksum, as with the native binary; (3) the binary is `root`-owned, mode `0755`, not writable by `islandr`, so the privileged process cannot be tampered with from the app; (4) argument-vector execution, never a shell string.

## Alternatives considered (Pugh Matrix)

Baseline: **Unix socket proxy — host daemon** (the decision). +1 better, 0 equal, −1 worse.

| Criterion (weight)                           | Socket proxy — host daemon (baseline) | Socket proxy — privileged sidecar | `--cap-add NET_ADMIN` + `--network host` | Docker socket mount | `--pid=host` + nsenter | systemd only (no Docker) |
| -------------------------------------------- | ------------------------------------- | --------------------------------- | ---------------------------------------- | ------------------- | ---------------------- | ------------------------ |
| Container gets no host capabilities (5)      | 0                                     | −1                                | −1                                       | −1                  | −1                     | +1                       |
| Blast radius if container is compromised (4) | 0                                     | −1                                | −1                                       | −1                  | −1                     | +1                       |
| Supports production Docker deployments (4)   | 0                                     | +1                                | +1                                       | +1                  | 0                      | −1                       |
| Auditable allowed-op surface (3)             | 0                                     | 0                                 | −1                                       | −1                  | −1                     | +1                       |
| Deployment complexity (2)                    | 0                                     | +1                                | +1                                       | +1                  | 0                      | +1                       |
| **Weighted total**                           | **0**                                 | **−3**                            | **−6**                                   | **−6**              | **−24**                | **+10**                  |

Notes:

- **Socket proxy — privileged sidecar** — runs the proxy in its own `--network host` + `CAP_NET_ADMIN` container, so the deployment is `docker compose up` with no host binary, and the app still stays unprivileged. That shrinks the *privileged attack surface* to the ~150-line proxy. But on a proxy compromise the attacker holds raw `CAP_NET_ADMIN`, which the JSON allowlist cannot bound — unlike the host daemon, whose scoped sudoers still constrain a compromised proxy. The −3 reflects trading worst-case containment for deployment convenience.
- **`--cap-add NET_ADMIN`** — with `--network host`, `CAP_NET_ADMIN` inside the container spans all network namespaces on the host; a compromised process has root-equivalent control over every nftables table and interface. Scores identically to Docker socket on the table but the failure mode is different (capability escape vs. container spawn escalation).
- **Docker socket mount** — `docker.sock` allows spawning `--privileged` containers, which is full host escape. Equally dangerous but via a different path.
- **`--pid=host` + nsenter** — equivalent to root on the host; worst option.
- **systemd only** scores +10 and is the baseline strategy — it is the right answer when Docker is not required. The proxy is chosen for 0.11.0 specifically to enable production Docker deployments without surrendering privilege isolation. `systemd only` scores −4 on "supports production Docker" which is the requirement that drives this ADR.

## Consequences

- 0.11.0 adds `islandr-proxy` as a second deliverable (binary + systemd unit).
- The real `WgAdapter` and `NftAdapter` in islandr gain a socket-client mode alongside the existing `ProcessBuilder` mode, plus a degraded "enforcement unavailable" state (probe + pending-config reconcile on connect). The published image runs this real adapter; the mock stays for dev/CI only.
- The Docker image graduates from demo-only to a full evaluation-and-config experience from a bare `docker run`, and becomes enforcement-capable once a socket proxy is attached — same image, no rebuild.
- The GUI gains a proxy-availability indicator: an "enforcement unavailable" banner with install instructions and a per-change "saved, not yet enforced" state. This is a new UI requirement and warrants a use-case in `spec.md` and a runtime scenario in arc42 Ch. 6.
- Enforcement keeps an irreducible one-time host install (`install.sh`: `islandr` user, scoped sudoers, `wg0`, proxy binary + systemd units); `docker run` of the app never bootstraps it. The privileged-sidecar shortcut that would have avoided the host binary was rejected — see Decision and the Pugh matrix.
- **R-120** — The proxy socket must be owned by `islandr:islandr` with mode `0600`. If the socket is world-readable, any local process can send commands. Mitigation: systemd `RuntimeDirectory=islandr` sets ownership automatically.
- **R-121** — The JSON protocol is unauthenticated (Unix socket ownership is the only gate). If another process runs as `islandr` user, it can send proxy commands. Mitigation: same as ADR-0011 R-111 — access as `islandr` already implies islandr is compromised; blast radius is still bounded to the WireGuard and nftables allowlist.
- **R-122** — In the degraded state an operator may believe rules are enforced when no proxy is attached, leaving the network open. Mitigation: the GUI must show the "enforcement unavailable" banner and label every unenforced change as pending; the degraded adapter never fakes success (unlike the dev/CI mock), so the gap is always visible.

## References

- [ADR-0011](0011-process-privilege-model.md) — process privilege model this extends to Docker
- [ADR-0003](0003-nftables-replaces-ufw.md) — nftables atomic reload pattern
