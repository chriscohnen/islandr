# ADR-0012 — Docker deployment via Unix socket proxy (v2)

**Status:** Proposed
**Date:** 2026-06-06
**Deciders:** Christian Cohnen

## Context

Islandr v1 ships a Docker image for demo and dev use only. The image uses the mock WireGuard and nftables adapters — it cannot manage real peers or firewall rules. Production deployments require the native binary under systemd (ADR-0011).

The reason: `nft` and `wg` run on the host kernel, not inside the container. To call them from a container, Docker would need `--cap-add NET_ADMIN` and `--network host`. With `--network host` that capability spans all network namespaces visible to the host — a compromised islandr process could manipulate any nftables table and any network interface on the machine. This violates ADR-0011's principle of least privilege.

Other escape hatches are worse: the Docker socket (`/var/run/docker.sock`) allows spawning privileged containers and is a full host-escape vector. `--pid=host` + `nsenter` is equivalent to root.

There is no safe way to call host-privileged tools from a container without a mediating process that holds the privilege on behalf of the container.

## Decision

In v2, a **Unix socket proxy** (`islandr-proxy`) runs as a systemd service on the host alongside the container. The container mounts only the proxy socket — no capabilities, no host PID namespace, no Docker socket.

```
Container                          Host
──────────────────────────────────────────────────────────────
islandr-binary
  │
  │  JSON over Unix socket:
  │  {"op":"nft_reload","path":"/var/lib/islandr/ruleset.nft"}
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

| Op | Command | Constraints |
|----|---------|-------------|
| `nft_reload` | `sudo nft -f <path>` | path must be `/var/lib/islandr/ruleset.nft` |
| `nft_flush` | `sudo nft flush ruleset islandr` | no args |
| `wg_set_peer` | `sudo wg set wg0 peer <pubkey> allowed-ips <cidr>` | interface fixed to `wg0`; pubkey and CIDR validated by format |
| `wg_remove_peer` | `sudo wg set wg0 peer <pubkey> remove` | same constraints |
| `wg_show` | `sudo wg show wg0 dump` | read-only |

Anything outside this list is rejected with an error — the proxy has no shell, no exec, no wildcard.

### Container run (v2)

```bash
docker run \
  -v /run/islandr/proxy.sock:/run/islandr/proxy.sock \
  -v /var/lib/islandr:/var/lib/islandr \
  -p 8080:8080 \
  ghcr.io/chriscohnen/islandr:latest
```

No `--cap-add`, no `--network host`, no `--privileged`.

### Protocol

Line-delimited JSON over a Unix domain socket (SOCK_STREAM). Request: `{"op":"...", ...fields}`. Response: `{"ok":true}` or `{"ok":false,"error":"..."}`. Synchronous — the proxy processes one request at a time per connection.

### islandr-proxy implementation

A small Go or Rust binary (< 300 lines), statically compiled, installed alongside the islandr native binary. Alternatively a shell script with `socat` for a first prototype. The proxy itself runs as `islandr` user with the same scoped sudoers rules as ADR-0011.

## Alternatives considered

| Alternative | Score vs. proxy |
|-------------|----------------|
| `--cap-add NET_ADMIN` | -1 — gives container process-level CAP_NET_ADMIN across all namespaces; with `--network host` equivalent to root on the host network stack |
| Docker socket mount | -1 — full host escape; allows spawning `--privileged` containers |
| `--pid=host` + nsenter | -1 — equivalent to root; no boundary |
| systemd only (no Docker) | 0 — valid for v1; rules out operators who prefer containers for process isolation, log aggregation, and image-based deployments |
| **Unix socket proxy** | baseline — least privilege preserved; container stays unprivileged; proxy is small and auditable |

## Consequences

- v2 adds `islandr-proxy` as a second deliverable (binary + systemd unit).
- The real `WgAdapter` and `NftAdapter` in islandr gain a socket-client mode alongside the existing `ProcessBuilder` mode.
- The Docker image graduates from demo-only to production-capable in v2.
- **R-034** — The proxy socket must be owned by `islandr:islandr` with mode `0600`. If the socket is world-readable, any local process can send commands. Mitigation: systemd `RuntimeDirectory=islandr` sets ownership automatically.
- **R-035** — The JSON protocol is unauthenticated (Unix socket ownership is the only gate). If another process runs as `islandr` user, it can send proxy commands. Mitigation: same as ADR-0011 R-031 — access as `islandr` already implies islandr is compromised; blast radius is still bounded to the WireGuard and nftables allowlist.

## References

- [ADR-0011](0011-process-privilege-model.md) — process privilege model this extends to Docker
- [ADR-0003](0003-nftables-replaces-ufw.md) — nftables atomic reload pattern
