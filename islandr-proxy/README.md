# islandr-proxy

Host-side privileged helper for the containerised **islandr** app. It lets an
**unprivileged** container drive WireGuard and nftables on the host without
`CAP_NET_ADMIN`, `--network host`, or a Docker-socket mount. See
[ADR-0012](../docs/adr/0012-docker-socket-proxy.md) for the rationale and the
Pugh matrix; the JVM client is `de.chriscohnen.islandr.proxy.ProxyClient`.

## What it does

Listens on a systemd-activated Unix socket, reads **one line-delimited JSON
request per connection**, and runs a fixed allowlist of commands as **argument
vectors** (never a shell string). Anything outside the allowlist is rejected.

| Op | Request | Command (via scoped `sudo`) |
|---|---|---|
| `wg_set_peer` | `{op, pubkey, allowedIps, presharedKey?}` | `wg set wg0 peer <pubkey> allowed-ips <cidr>[ preshared-key <file>]` |
| `wg_remove_peer` | `{op, pubkey}` | `wg set wg0 peer <pubkey> remove` |
| `wg_show` | `{op}` | `wg show wg0 dump` → `{ok, dump}` |
| `nft_validate` | `{op}` | `nft -c -f /var/lib/islandr/ruleset.nft` |
| `nft_reload` | `{op}` | `nft -f /var/lib/islandr/ruleset.nft` |

Response is always one JSON line: `{"ok":true}` / `{"ok":true,"dump":"…"}` /
`{"ok":false,"error":"…"}`.

Fixed server-side (never from a request): the interface (`wg0`) and the ruleset
path. Keys must be base64 of exactly 32 bytes; every `allowedIps` element must be
a valid CIDR — otherwise the request is rejected before any exec. Preshared keys
are written to a short-lived `0600` file (`wg` never takes them on the command
line) and removed immediately after the call.

## Trust model

Zero third-party dependencies on purpose — the trusted computing base is the Go
stdlib plus this ~300-line source, nothing external to audit. Socket ownership
(`0600 islandr:islandr`, set by systemd) is the only authentication; access as
the `islandr` user already implies islandr is compromised, and the blast radius
is still bounded to the wg/nft allowlist (R-121).

## Build

```bash
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o islandr-proxy .
go test ./...
```

Reproducible, statically linked (~1.9 MB), no cgo.

## Install (host, one-time)

```bash
sudo ./install.sh ./islandr-proxy
```

Creates the `islandr` user, scoped sudoers, `/run/islandr`, and the
socket-activated systemd units, then enables `islandr-proxy.socket`. Mount the
socket into the container:

```
-v /run/islandr/proxy.sock:/run/islandr/proxy.sock
```

`install.sh` does **not** set up the `wg0` interface — see
[docs/install.md](../docs/install.md).

## Files

- `main.go` / `handler.go` / `server.go` — daemon, dispatch/validation, socket loop
- `systemd/` — `.socket`, `.service`, `islandr.tmpfiles.conf`, `islandr-proxy.sudoers`
- `install.sh` — idempotent host bootstrap
