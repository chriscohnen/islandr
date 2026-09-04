# Why the systemd unit and sudoers file look like this

[`setup-hub.sh`](setup-hub.sh) writes a deliberately narrow privilege setup.
Several of the settings look wrong at first glance and have been "fixed" into
an outage more than once. This page is the reasoning; the script itself only
carries one-line pointers back here.

The short version: **islandr never runs as root.** It runs as the unprivileged
`islandr` user and borrows exactly two privileges — a scoped `sudo` grant for
`nft` and `wg`, and `CAP_NET_BIND_SERVICE` to bind ports below 1024. See
[ADR-0011](../adr/0011-process-privilege-model.md).

## The sudoers file

```
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -c -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft delete table inet islandr
islandr ALL=(root) NOPASSWD: /usr/bin/wg set wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg syncconf wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg show wg0
islandr ALL=(root) NOPASSWD: /usr/bin/wg show wg0 dump
```

**The paths must be absolute and must match the host.** The script resolves
`wg` and `nft` with `command -v` rather than hardcoding `/usr/sbin` and
`/usr/bin`, because sudo matches on the path it will actually execute and
distributions disagree about where these live.

**The interface name is baked in.** Running the service against an interface
the sudoers file does not name fails with permission errors in the journal, not
with a startup error. Set `ISLANDR_WG_INTERFACE` to the same name.

**The wildcard on the nft path is required.** `RealNftablesAdapter` stages a
freshly named temp file per apply (`islandr-nft-<random>.nft`) under
`/var/lib/islandr`, not a fixed filename. The grant is scoped to that directory,
which is why the adapter does not stage into `/tmp`.

**Network diagnostics have no entry here on purpose.** Neither `ping` nor
`tracepath` ([ADR-0025](../adr/0025-network-diagnostic-helpers.md)) runs through sudo.
`tracepath` needs no elevation on Linux at all, and `ping` normally doesn't
either: iputils ships the binary with a `cap_net_raw` file capability, or the
distribution's `net.ipv4.ping_group_range` sysctl permits an unprivileged ICMP
socket outright. If your host has neither and pings fail with "Operation not
permitted", the fix is local to that host — `setcap cap_net_raw+ep $(command -v
ping)`, or widening `ping_group_range`. A sudoers entry would not help, because
islandr's ping invocation does not use sudo.

### Do not silence the journal noise with `!pam_session`

The 30-second activity poller calls `wg show` through sudo, and each tick logs
sudo's own line plus a PAM session open/close. It is tempting to quiet that with
a scoped `Defaults!cmnd_alias !pam_session` rule. **Don't.**

The unit runs with `ProtectSystem=strict`, which makes `/run` read-only for the
service and everything it spawns, sudo included. Sudo tolerates the resulting
`/run/sudo/ts: Read-only file system` as long as it can still complete a PAM
session. Disabling `pam_session` removes that tolerance, and sudo falls back to
demanding interactive authentication — breaking *every* sudo call, not just the
noisy one. Confirmed in production on 2026-07-21.

## The systemd unit

### `NoNewPrivileges=false`

Counter-intuitive for a hardened unit, and load-bearing. `NoNewPrivileges=true`
blocks the setuid bit that sudo relies on to become root. Leaving it on fails
silently until the first sudo call, then:

```
sudo: unable to change to root gid: Operation not permitted
```

### `AmbientCapabilities=CAP_NET_BIND_SERVICE`, without a bounding set

islandr binds ports 80 and 443 for built-in TLS
([ADR-0015](../adr/0015-builtin-tls-termination.md)) and optionally port 53 for
the resource DNS resolver ([ADR-0023](../adr/0023-resource-dns-resolver-hand-rolled.md)). The ambient
capability grants exactly "may bind ports below 1024" — nothing else, and not
root.

It is deliberately **not** paired with
`CapabilityBoundingSet=CAP_NET_BIND_SERVICE`. The bounding set applies to the
whole process tree, sudo children included. Restricting it there denies sudo the
`CAP_SETUID`/`CAP_SETGID` it needs to actually become root after its setuid-root
exec, and every `nft`/`wg` call fails with the same "unable to change to root
gid" error as above. Confirmed the hard way on 2026-08-08.

### `PrivateTmp=true`

Safe, and better than the alternative. The nft rulesets are staged in
`/var/lib/islandr` (the only path the sudo grant covers), and the one real
`/tmp` user — the short-lived file for `wg set ... preshared-key` — is written
and consumed inside the same mount namespace, which sudo children inherit. The
private namespace also keeps the preshared key out of a world-readable `/tmp`.

### `ProtectSystem=strict` + `ReadWritePaths=/var/lib/islandr`

The whole filesystem is read-only except the data directory. Note the
interaction with sudo described under `!pam_session` above.

### `RestartSec=10` and the `StartLimit*` pair

```ini
[Unit]
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Restart=on-failure
RestartSec=10
```

A misconfiguration — an unusable `QUARKUS_HTTP_PORT`, a missing WireGuard
interface — puts the service into a crash loop. At the systemd default of a few
seconds, the journal fills faster than you can read it and the first, actually
useful stack trace scrolls out of reach. Ten seconds plus "give up after 5
attempts in 5 minutes" leaves the unit `failed` with the real error still near
the end of `journalctl -u islandr`.

## Ports

islandr terminates TLS itself and can provision a Let's Encrypt certificate
([ADR-0019](../adr/0019-acme-hand-rolled-client.md)). **Port 80 must stay reachable
from the public internet** for the ACME HTTP-01 challenge, even after a real
certificate is issued — RFC 8555 always validates on port 80, and that is not
configurable on either side.

To run behind an existing reverse proxy instead, bind islandr to loopback ports
and let the proxy keep 80/443. See [reverse-proxy.md](reverse-proxy.md) for both
paths side by side.
