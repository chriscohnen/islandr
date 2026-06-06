# ADR-0011 — Process privilege model: unprivileged user + scoped sudo for nft and wg

**Status:** Accepted
**Date:** 2026-06-06
**Deciders:** Christian Cohnen

## Context

Islandr must call two privileged CLI tools at runtime:

- `nft` — to atomically reload the `inet islandr` nftables table (ADR-0003)
- `wg` / `wg-quick` — to add, update and remove WireGuard peers on the hub interface

Both tools require `CAP_NET_ADMIN`. The naive deployment runs the islandr process as `root` or with a full `CAP_NET_ADMIN` grant on the binary. Either approach violates the principle of least privilege: a compromised islandr process would have unrestricted access to all network interfaces, all firewall tables, routing, and — if running as root — the entire filesystem.

The risk is real. islandr is reachable from the internet (it serves the admin console and the OIDC callback). A remote-code-execution vulnerability in the Quarkus HTTP layer, the Jackson deserialiser, or any library in the dependency tree would hand an attacker full root access to the hub VM.

The same concern applies to the Docker deployment: granting `--cap-add NET_ADMIN` to the container gives the container process that capability across all network namespaces visible to it. `--network host` (required for WireGuard to work) makes the exposure even wider.

## Decision

Islandr runs as a **dedicated unprivileged system user** (`islandr`, no login shell, no sudo by default). The two privileged tools are made available through **scoped `sudoers` rules** that allow exactly the commands islandr needs — nothing else.

### Unprivileged user

```
# /etc/systemd/system/islandr.service (excerpt)
[Service]
User=islandr
Group=islandr
```

The `islandr` user:
- Has no password and no login shell (`/usr/sbin/nologin`)
- Owns `/var/lib/islandr/` (DB, temp files) and nothing else
- Cannot write to `/etc`, `/usr`, `/bin`, or any other system path

### Scoped sudoers rules

```sudoers
# /etc/sudoers.d/islandr
# Allow islandr to manage its own nftables table and WireGuard interface only.
# NOPASSWD because the process is non-interactive.
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -f /var/lib/islandr/ruleset.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -c -f /var/lib/islandr/ruleset.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft delete table inet islandr
islandr ALL=(root) NOPASSWD: /usr/bin/wg set wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg syncconf wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg-quick up wg0
islandr ALL=(root) NOPASSWD: /usr/bin/wg-quick down wg0
```

Key constraints:
- Only the **exact nft file path** `/var/lib/islandr/ruleset.nft` is allowed — not arbitrary file paths. islandr writes the ruleset to that fixed path before calling `sudo nft -f`.
- `wg set` and `wg syncconf` are scoped to `wg0` — not arbitrary interfaces.
- No wildcard `sudo ALL` is ever granted.
- `visudo -c` validates the file on deployment.

### Docker variant

For the Docker deployment, the same model applies inside the container:

```dockerfile
RUN useradd -r -s /usr/sbin/nologin islandr
COPY sudoers.d/islandr /etc/sudoers.d/islandr
RUN chmod 0440 /etc/sudoers.d/islandr
USER islandr
```

The container still requires `--cap-add NET_ADMIN` and `--network host`, but the *process inside* runs as the unprivileged `islandr` user and escalates only via the scoped sudoers rules. An RCE in the HTTP layer gets a shell as `islandr`, not as `root`.

### WireGuard interface ownership

The `wg0` interface is created by the operator before starting islandr (or by `wg-quick up wg0` via the scoped sudo). islandr does not own the interface in the OS sense — it only manages peer entries via `wg set`. This keeps interface creation (a rare, manual operation) separate from peer management (frequent, automated).

## Alternatives considered (Pugh Matrix)

Baseline: **unprivileged user + scoped sudoers** (the decision).

| Criterion (weight) | Scoped sudoers (baseline) | Run as root | CAP_NET_ADMIN on binary | Linux capabilities on binary |
|---|---|---|---|---|
| RCE blast radius (5) | 0 | -1 | -1 | 0 |
| Deployment simplicity (3) | 0 | +1 | 0 | -1 |
| No kernel capability exposure beyond nft/wg (4) | 0 | -1 | -1 | -1 |
| Audit trail (who called nft) (2) | 0 | -1 | -1 | -1 |
| Docker compatibility (3) | 0 | 0 | +1 | -1 |
| Works without custom kernel patches (2) | 0 | 0 | 0 | 0 |
| **Weighted total** | **0** | **−14** | **−13** | **−16** |

Notes:

- **Run as root** — simplest to deploy; unacceptable blast radius. An HTTP exploit is a full root shell.
- **CAP_NET_ADMIN on the binary** (`setcap cap_net_admin+ep`) — grants the capability to any process that executes the binary, not just to islandr. Also grants `CAP_NET_ADMIN` for *all* network operations (routing, interface creation, all tables), not just `wg0` and `inet islandr`. Not scoped enough.
- **Linux capabilities on the binary** (`cap_net_admin`, `cap_sys_admin` via ambient capabilities in the systemd unit) — possible but complex to configure correctly, particularly for child processes (`nft`, `wg`) that need to inherit the capability. Capability inheritance across `exec` is subtle and error-prone. Scoped sudoers is simpler and auditable.

## Consequences

**Positive**

- An RCE in the HTTP layer yields a shell as `islandr` — cannot write system files, cannot touch other network interfaces, cannot read root-owned secrets.
- `sudo` logs every privileged call to syslog (`auth.log`). The audit trail for firewall changes has an OS-level record independent of islandr's own audit log.
- The sudoers file is a small, reviewable artefact. An operator can read it in 30 seconds and understand exactly what islandr is allowed to do.
- Docker: the container still needs `--cap-add NET_ADMIN` and `--network host`, but the process-level exposure is minimised.

**Risks created**

- **R-030** — The fixed nft file path `/var/lib/islandr/ruleset.nft` must be writable only by the `islandr` user. If another process can write to that path, it can inject arbitrary nftables rules via the sudo grant. Mitigation: `chmod 700 /var/lib/islandr/`; only `islandr` user owns the directory.
- **R-031** — The `wg set wg0 *` wildcard allows any `wg set` arguments for `wg0`. A malicious caller that already has a shell as `islandr` could inject a peer with a crafted public key or allowed-IPs. Mitigation: access as `islandr` already implies islandr is compromised — the blast radius is still bounded to WireGuard peer management on `wg0`.
- **R-032** — The Docker image cannot safely call `nft`/`wg` on the host without elevated container capabilities (`--cap-add NET_ADMIN`, `--network host`), which violate least-privilege. Mitigation: the v1 Docker image uses mock adapters only (demo/dev). Production Docker support is deferred to v2 via a Unix socket proxy (ADR-0012).
- **R-033** — A distro update that changes the path of `nft` or `wg` (e.g. from `/usr/sbin/nft` to `/usr/bin/nft`) silently breaks the sudoers rule. Mitigation: deployment script uses `which nft` and `which wg` to verify paths match the sudoers file; CI smoke test calls `sudo -l -U islandr` and asserts the expected commands are listed.

**Accepted trade-offs**

- Slightly more complex initial setup than "just run as root". Offset by the deployment guide and the `setup-hub.sh` script in `docs/install/`.
- `sudo` adds one process fork per `nft`/`wg` call. At the call frequency islandr generates (reload on ACL change, not per-packet) this overhead is immeasurable.

## References

- [ADR-0003](0003-nftables-replaces-ufw.md) — nftables model and atomic reload pattern
- [ADR-0005](0005-hub-only-firewall.md) — firewall enforcement scope
- [docs/install/setup-hub.sh](../install/setup-hub.sh) — deployment script (sudoers block to be added)
- OWASP A05:2021 — Security Misconfiguration (running privileged services as root)
- `sudoers(5)` — man page for the sudoers file format and wildcard semantics
