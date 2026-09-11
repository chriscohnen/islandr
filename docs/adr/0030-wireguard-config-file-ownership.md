# ADR-0030 — Islandr configures peers with `wg set` and never writes `/etc/wireguard/<iface>.conf`

**Status:** Accepted (retroactive)
**Date:** 2026-09-11
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0011](0011-process-privilege-model.md) (the scoped-sudo model this depends on), [ADR-0012](0012-docker-socket-proxy.md) (the same write path in socket mode), [ADR-0003](0003-nftables-replaces-ufw.md) (the nftables side, which *does* own its table)

## Context

Islandr has always configured WireGuard peers at runtime: `RealWgAdapter` calls
`wg set <iface> peer …` for every create, update, key rotation, enable/disable
and delete. It has never written `/etc/wireguard/<iface>.conf`, and under
[ADR-0011](0011-process-privilege-model.md) it could not — the service runs as
an unprivileged user whose sudoers entry names `nft` and `wg` and nothing else.
The interface itself (private key, listen port, `PostUp`/`PostDown`, `Address`)
is created by the admin before Islandr is installed and stays theirs.

That was a deployment consequence nobody had written down as a decision, and in
0.21.0 three of its consequences surfaced within a day of each other:

1. **Peers set with `wg set` are kernel state.** A host reboot, or a
   `systemctl restart wg-quick@<iface>`, reloads the file and brings the
   interface back holding only the peers written in it. Measured on a live hub:
   12 peers before the restart, 3 after. Nothing converged the interface back to
   the database — the activity poller only reads, and `PeerScheduleJob` acts on
   state transitions rather than on drift — so the peers stayed gone until an
   admin edited each one.
2. **Peers in the file that Islandr never imported are invisible to it.** The
   address allocator built its "taken" set from the database alone, so it could
   hand out an address a foreign peer already holds. `AllowedIPs` is also the
   inbound filter and each address belongs to exactly one peer, so `wg set`
   moves it to the new peer and everything looks correct — until the next
   interface reload gives it back and traffic for that address reaches a device
   the admin does not manage.
3. **Removing Islandr removes the peers it configured.** The peers in the file
   survive an uninstall precisely because Islandr never touched them; its own
   peers do not, because they were never written anywhere but the kernel.

The question this ADR settles is therefore not "should Islandr write the file"
in the abstract, but whether these three consequences are worth the property
they buy.

## Decision

**Keep `wg set` as the only write path. The interface config file stays the
admin's, and Islandr's peer set lives in its database.** The property that buys:
installing Islandr on a hub that already runs WireGuard changes nothing about
that hub's configuration, and removing it changes nothing either. There is one
writer per artifact — the admin owns the file, Islandr owns the kernel peer set
and its own nftables table — so the two can never disagree about who wrote what.

The three consequences are answered with mechanisms rather than accepted:

- **Convergence at startup.** `WgBootstrap` re-pushes every enabled peer once
  the boot probe answers, mirroring what `FirewallBootstrap` already does for
  nftables.
- **Convergence while running.** `ActivityPoller` already reads the live key set
  every 30s; anything enabled in the database and missing from the interface is
  pushed back on the same tick. Convergence is a running property, not a startup
  event — a `wg-quick` restart under a running Islandr is repaired without one.
- **Address allocation reads the interface, not just the database.** Both the
  suggestion and the manual-assignment validation exclude addresses held by
  peers Islandr does not manage, and the rejection names the import as the fix.
  Best-effort by design: if `wg` cannot be reached, only the database is
  consulted, because an unreachable enforcement plane must not block peer
  creation.
- **Removal is survivable.** The Peers view exports every enabled peer as
  `[Peer]` blocks for the server config — a download the admin appends, not a
  file Islandr writes. No `[Interface]` section: that part stays theirs on the
  way out as well.
- **Unmanaged peers are visible.** The dashboard reports how many peers on the
  interface Islandr does not manage, instead of showing them only to an admin
  who happens to open the import dialog.

All of these writes are suppressed while `firewallDryRun` is set, checked at the
call site rather than left to the adapter, so a fresh installation on an adopted
hub changes nothing until the admin activates enforcement deliberately.

## Alternatives considered (Pugh Matrix)

Baseline: **A — `wg set` only, with startup and running reconcile** (the decision).

| Criterion (weight) | A: `wg set` + reconcile (baseline) | B: Islandr owns and rewrites `<iface>.conf` | C: generate a file and apply it with `wg syncconf` |
|---|:---:|:---:|:---:|
| An existing hub is unchanged by installing/removing Islandr (5) | +1 *(the file is never opened)* | −1 *(the file is rewritten; comments, ordering and hand-written peers are at the mercy of a serializer)* | −1 *(`syncconf` removes every peer not in the generated file — the hand-maintained ones included)* |
| Peer set survives a reboot without Islandr (4) | −1 *(kernel state only; peers are absent until Islandr starts and re-pushes)* | +1 *(`wg-quick` brings everything back with no service running)* | +1 *(same, if the generated file is the one `wg-quick` reads)* |
| Privilege footprint (4) | +1 *(`nft` and `wg` only, as ADR-0011 scoped it)* | −1 *(needs write access to `/etc/wireguard`, i.e. a new privileged path for the most sensitive file on the host)* | 0 *(a staged file under `/var/lib/islandr` plus one `wg syncconf` sudoers line — the same shape ADR-0003 uses for rulesets)* |
| One writer per artifact (4) | +1 *(no shared file; drift is detectable by comparison)* | −1 *(admin and Islandr both edit the same file — the classic two-writer problem)* | 0 *(the generated file is Islandr's, but its content must mirror foreign peers to preserve them)* |
| Feasible with what Islandr knows (3) | +1 *(peers are all it needs)* | −1 *(the `[Interface]` section carries the server private key, which Islandr does not hold — only the public key is in Settings)* | −1 *(same: a `syncconf` file normally carries `[Interface]`, and whether a peers-only file leaves the interface untouched is unverified)* |
| Convergence complexity (2) | −1 *(needs an explicit boot re-push and a drift check in the poller)* | +1 *(the file is the state; nothing to reconcile)* | +1 *(one declarative command converges everything)* |

**Weighted:** A = +18, B = −13, C = −7.

B loses on the property the product is sold on and on privileges it would have
to acquire. C is the interesting one: its deletion semantics would give
convergence *and* the ability to remove a foreign peer in one command, and the
staged-file-plus-sudoers shape is already precedented by the nftables path. It
loses here for two reasons that are about this system rather than about the
tool: removing peers not in the file is exactly the behaviour an adopted hub
must not have unless Islandr first adopts every foreign peer into its own
generated file, and the file normally carries an `[Interface]` section built
around a private key Islandr does not have. If Islandr ever decides it *owns*
the interface, C is the right mechanism and this ADR should be superseded rather
than amended — the private-key question must be answered first.

## Consequences

- Because the nftables table is applied at startup and does not survive a
  reboot, the service unit is ordered **before** `wg-quick@<iface>`: otherwise
  the interface would come up with the file's peers while no table existed, and
  the kernel's own FORWARD policy is `accept` when nothing else is loaded. This
  closes the boot window but not the case where Islandr fails to start at all —
  a hub that must stay closed then needs a persistent ruleset loaded at boot,
  which Islandr does not install today (**R-192**).
- The tunnel's peer set depends on Islandr running. A hub whose interface is
  reloaded while Islandr is down comes back with only the peers in the file, and
  stays that way until the service starts (**R-190**).
- Peers in the file that Islandr does not manage keep working, and because the
  generated ruleset filters forwarded traffic only, they reach the hub itself —
  admin console, SSH, the DNS resolver (**R-191**).
- Blocking such a peer durably is not possible without either editing the file
  or maintaining a denylist Islandr enforces on every reconcile. Neither is
  built; the dashboard makes the peers visible and the import makes them
  manageable, which is where 0.21.0 stops.
- Backup/restore semantics stay clean: the database is the whole of Islandr's
  peer state, so `docs/install.md`'s backup instructions need no file-level
  coordination with `/etc/wireguard`.

## References

- [ADR-0011](0011-process-privilege-model.md) — why the service holds `nft`/`wg` and nothing else
- [ADR-0003](0003-nftables-replaces-ufw.md) — the artifact Islandr *does* own end to end
- `WgBootstrap`, `ActivityPoller#reconcileDrift`, `PeerService#foreignAddressesOnInterface`, `PeerService#exportPeersAsWgConf`
- README, "How Islandr treats your WireGuard config"
