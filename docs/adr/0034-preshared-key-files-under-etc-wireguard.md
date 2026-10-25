# ADR-0034 — Preshared-key files are written under /etc/wireguard

**Status:** Accepted (implemented in 1.2.0)
**Date:** 2026-10-23
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0011](0011-process-privilege-model.md) (the sudo scope, unchanged by this), [ADR-0012](0012-docker-socket-proxy.md) (the proxy, which has the same problem), [ADR-0030](0030-wireguard-config-file-ownership.md) (Islandr does not write `<iface>.conf` — still true), [ADR-0033](0033-what-1-0-promises.md) (the on-disk layout is part of the promise)

## Context

`wg` never takes a preshared key as an argument. The process list is readable
by every account on a host, so `wg set … preshared-key` expects the path of a
file and reads the key from it. Islandr therefore writes each preshared key to
a short-lived 0600 file, calls `wg`, and deletes the file: the hub in the JVM's
temp directory (the service's private `/tmp` under `PrivateTmp=true`),
`islandr-proxy` in `/run/islandr`.

Ubuntu 26.04 ships an AppArmor profile for `/usr/bin/wg` in enforce mode:

```
profile wg /usr/bin/wg flags=(attach_disconnected){
  include <abstractions/base>
  include <abstractions/nameservice-strict>
  capability net_admin,
  capability net_bind_service,
  ...
  # wireguard configuration and key files
  file rw @{etc_rw}/wireguard/{,**},
  ...
}
```

Its only file rule is `/etc/wireguard` and below. It grants `net_admin` but not
`dac_override` or `dac_read_search`, so running `wg` as root does not get
around it. Measured on the GitHub runner image (ubuntu-26.04, 20261012) by
handing `wg set … private-key` a root-owned 0600 key file in each candidate
directory:

| Directory | Result |
|---|---|
| `/etc/wireguard` | read |
| `/root` | denied |
| `/tmp` | denied |
| `/var/tmp` | denied |
| `/home/runner/work/_temp` | denied |

with the kernel logging `apparmor="DENIED" operation="open" profile="wg"` for
each denied path. `wg` itself reports only `fopen: Permission denied`. A key
piped in on standard input from the calling process is refused the same way:
`/dev/stdin` resolves to a pipe the calling user owns, and reading it needs the
same capabilities.

A second measurement settled the ownership. A key file the *service user*
writes, in a 0700 directory it owns, mode 0600: refused — the kernel logs the
denied `dac_override`. The same file in a directory owned by the service user,
group `root`, setgid, mode 2750, written 0640: read, and the key reached the
interface. In that layout the service user can still delete its file, and
another account can neither read it nor list `/etc/wireguard`.

On such a host every peer created with a preshared key fails with HTTP 500, in
both deployments. Peers without one are unaffected. Preshared keys are an
admin-only option and off by default, so this hits a minority of peers on a
minority of hosts today — and every such host as 26.04 spreads.

## Decision

Islandr writes the preshared-key file to **`/etc/wireguard/islandr`**, a
directory of its own:

- **Owned by the service user, setgid `root`, mode 2750.** The profile denies
  `wg` `dac_override`, so root's `wg` reads a file only if the ordinary
  permission bits let it — a 0600 file owned by `islandr` is closed to it, and
  so is a 0700 directory. Measured on the image: refused. With the setgid bit
  every key file is born in group `root`; Islandr writes it 0640, and group
  read is how root's `wg` gets in. Nobody outside group `root` can read it, the
  service can still delete it, and the file is deleted right after the call,
  as before.
- **Passage, not access.** The service user has to reach the directory through
  `/etc/wireguard`, which is 0700 root:root on a stock host. The installers set
  its group to `islandr` and grant that group execute only — no read, so nobody
  can list the directory, and the operator's files keep their own modes. If the
  directory already belongs to a group other than `root`, the installers leave
  it alone and say so.
- **Opened in the unit.** `ProtectSystem=strict` (hub) and `=full` (proxy) make
  `/etc` read-only; `ReadWritePaths=-/etc/wireguard/islandr` opens exactly this
  directory. The leading `-` keeps the unit starting where the directory does
  not exist.
- **Falls back, does not fail.** A missing or unwritable directory makes Islandr
  use the old location, logged once. That keeps every host installed before
  1.2.0 working: it either has no such AppArmor profile, where the old location
  still works, or it was already failing.
- **Reports, does not repair.** `update.sh` touches nothing under `/etc`
  ([ADR-0031](0031-fail-closed-boot-ruleset.md) set that line). Where the
  profile exists and the setup is incomplete, it says so and links the manual
  steps in `docs/install.md`.
- **Cleans up after a crash.** At startup the hub and the proxy each delete any
  `psk-*` file in the directory older than one minute. A key file lives for one
  `wg` call, bounded at five seconds.

The configuration key is `islandr.wg.psk-dir` (hub) and `ISLANDR_PSK_DIR`
(proxy), both defaulting to `/etc/wireguard/islandr`.

## Alternatives considered (Pugh Matrix)

| Criterion (weight) | A: own directory under `/etc/wireguard` (chosen) | B: ship an AppArmor override in `/etc/apparmor.d/local/wg` | C: no file — set the key over netlink | D: document the limitation, fix later |
|---|---|---|---|---|
| Works on Ubuntu 26.04 (5) | +1 | +1 | +1 | −1 |
| Leaves the host's security policy alone (4) | 0 *(changes the group and one mode bit of `/etc/wireguard`)* | −1 *(widens a confinement profile Ubuntu chose to enforce)* | +1 | +1 |
| No new privileges for the service (4) | +1 *(sudo scope unchanged)* | +1 | 0 *(the proxy could do it; the native hub would need a netlink library instead of the CLI)* | +1 |
| Hosts installed before 1.2.0 keep working (4) | +1 *(fallback)* | +1 | +1 | +1 |
| Key material exposure (3) | −1 *(on disk under `/etc` for one call; mitigated by the sweep — R-198)* | 0 *(stays on tmpfs or private `/tmp`)* | +1 *(never on disk)* | 0 |
| Effort and risk for 1.2.0 (2) | 0 *(three installers, two code paths, two E2E checks)* | 0 *(one file per installer, but a policy file to maintain against Ubuntu's)* | −1 *(new dependency or a hand-written netlink client in two languages)* | +1 |
| **Weighted total** | **+10** | **+9** | **+14** | **+9** |

C scores highest and stays the long-term direction: a key that never touches a
file is the cleanest answer. It is not a 1.2.0 change — it means a netlink
client in Java and in Go, and that is a feature, not a fix. A is the smallest
change that is safe today and does not stand in C's way: once the key travels
over netlink, the directory and its passage can simply be removed.

## Consequences

- **Positive:** preshared keys work on Ubuntu 26.04 in both deployments, with
  no new sudo rights and no change to the host's AppArmor policy. The README's
  promise that Islandr never writes `<iface>.conf` stays true.
- **Negative — R-197:** the feature now depends on a group and a mode bit on a
  directory Islandr does not own, and on a line in the unit. Anything that
  resets either breaks preshared keys again, quietly, for the next such peer.
  Mitigated by `update.sh`'s report, by an error message that names the cause,
  and by both E2E jobs asserting the modes on every release candidate.
- **Negative — R-198:** key material sits on disk under `/etc` for one call,
  where it used to sit on tmpfs. Mitigated by the 2750/0640 modes, deletion
  right after the call and the startup sweep.
- **On-disk layout ([ADR-0033](0033-what-1-0-promises.md)):** the change is
  additive. A new directory appears; nothing existing moves or is removed, and
  a host without it keeps its earlier behaviour — so it fits a minor release.
- **Hubs installed before 1.2.0 on Ubuntu 26.04** need two manual steps. They
  are told so by `update.sh` and by the error on the first failing peer.

## References

- `docs/install.md` — "Installing on a hub that already runs WireGuard" (what
  the installer now adds) and "Preshared keys on Ubuntu 26.04" (the manual
  steps for older hubs)
- `RealWgAdapter#createPskFile`, `RealWgAdapter#sweepStalePskFiles`
- `islandr-proxy`: `writePsk`, `pskDir`, `sweepStalePsk`
- `.github/workflows/ci.yml` — both E2E jobs assert the directory modes and
  that a peer with a preshared key reaches the interface
