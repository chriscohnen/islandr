# ADR-0003 — nftables replaces ufw on the hub VM

**Status:** Accepted
**Date:** 2026-05-30
**Deciders:** Christian Cohnen

## Context

Islandr must enforce ACLs at the packet level on the hub VM: for each enabled peer, the set of target networks the peer's groups allow, and a default-deny for everything else.

The hub VM currently uses ufw (Uncomplicated Firewall) for hand-written rules. ufw is a thin wrapper around iptables (or nftables underneath, depending on distro version) optimized for human-typed commands. It is not optimized for programmatic management.

Islandr's enforcement loop is: ACL changes → recompute ruleset → validate → atomically reload. That loop runs on every peer create/disable/delete and every group/network change. Failure modes that matter:

1. A bad recompute that breaks SSH access to the hub VM mid-reload. Operator locks themselves out.
2. A partial apply where some rules are new and others are old. Inconsistent enforcement until the next reload.
3. A persistent-state file (e.g. `/etc/ufw/user.rules`) that drifts from the in-kernel state because Islandr writes one but `ufw` rewrites the other.

We need a firewall layer whose model maps cleanly onto: "atomically replace this table with this content; validate first".

## Decision

Use **nftables** directly via the `nft` CLI. Drop ufw on the hub VM entirely.

- Islandr owns a dedicated nftables table named `inet islandr`. It never touches other tables.
- The ruleset is **fully recomputed** from DB state on every relevant change. No partial-update path exists.
- The recompute writes a string to a temp file, validates with `nft -c -f <tempfile>`, and on success replaces the live table with `nft -f <tempfile>`. nftables guarantees atomic table replacement.
- If validation fails: log the `nft` stderr, keep the existing live table, surface the error in the UI.
- ACL target granularity is per `(peer.assignedIP, resource.ip, transport, port)` tuple — one `accept` rule per tuple. Site CIDRs are **not** used as nftables targets; they appear only in peer `.conf` `AllowedIPs` for routing. ([ADR-0006](0006-resource-level-acl.md) defines this granularity and supersedes the named-set approach considered during initial design.) This keeps the ruleset explicit, auditable, and easy to verify with `nft list ruleset`.
- ufw is masked at the systemd level (`systemctl mask ufw`) so an admin reflex of `ufw allow 22` doesn't conflict with the Islandr-owned table.
- SSH and the Islandr HTTPS port are **not** managed in the `islandr` table. They live in a separate `inet filter input` table maintained out-of-band by the operator, so an Islandr bug cannot lock the operator out.

Example generated ruleset (illustrative — peer 10.8.0.5 has RDP+SSH to Terminal-01; peer 10.8.0.6 has RDP only):

```nftables
table inet islandr {
  chain forward {
    type filter hook forward priority 0; policy drop;

    iifname != "wg0" oifname != "wg0" accept comment "islandr:not-wg-traffic, leave to host"
    iifname "wg0" ip saddr 10.8.0.5 ip daddr 10.20.0.5 tcp dport 3389 accept
    iifname "wg0" ip saddr 10.8.0.5 ip daddr 10.20.0.5 tcp dport 22 accept
    iifname "wg0" ip saddr 10.8.0.6 ip daddr 10.20.0.5 tcp dport 3389 accept
  }
}
```

## Alternatives considered (Pugh Matrix)

Baseline: **nftables direct** (the decision).

| Criterion (weight)            | nftables direct (baseline) | ufw (status quo) | iptables direct | firewalld |
|-------------------------------|----------------------------|------------------|-----------------|-----------|
| Atomic ruleset replacement (5)| 0                          | -1               | -1              | 0         |
| Validation before apply (`-c`) (4) | 0                     | -1               | -1              | 0         |
| Programmatic management ergonomics (4) | 0                | -1               | -1              | 0         |
| Named sets for ACL targets (3) | 0                         | -1               | -1              | -1        |
| State file vs. in-kernel drift risk (3) | 0               | -1               | 0               | -1        |
| Modern / future-proof (2)     | 0                          | 0                | -1              | 0         |
| Operator familiarity (2)      | 0                          | +1               | 0               | -1        |
| **Weighted total**            | **0**                      | **−16**          | **−15**         | **−9**    |

Notes:

- **ufw, atomic replacement** — ufw rewrites rules incrementally; there is no "validate then atomically swap" idiom. Risk of inconsistent state during reload.
- **iptables direct** — `iptables-restore` does support atomic-ish replace, but the rule format is less expressive (no named interval sets for CIDRs), and iptables is on its way out.
- **firewalld** — supports zones and runtime/permanent split, but the model is awkward for "programmatically own one logical block of rules" and there's no clean way to validate-then-swap a whole zone.

The matrix is decisive. The only column where ufw wins is operator familiarity, which is also the column that creates the trap: an operator who reflexively types `ufw allow` adds rules outside Islandr's model and creates exactly the drift this ADR is trying to prevent.

## Consequences

**Positive**

- Atomic, validated reloads. The loop is `nft -c -f` → `nft -f`; either both rules and sets update together or neither does.
- One source of truth: the DB. nftables state is always a function of `SELECT … FROM peers JOIN users JOIN groups JOIN networks`.
- Named sets keep the rule count manageable as peers and networks grow.
- nftables is the modern Linux firewall — Debian 11+ and Ubuntu 22.04+ ship it by default.

**Risks created**

- **R-020** — A bug in the ruleset generator could produce a syntactically valid but semantically wrong ruleset that, for instance, drops all traffic. Validation (`-c`) catches syntax, not intent. Mitigation: integration test the generator against known peer/group/network fixtures; smoke test in CI that golden-file rulesets parse and behave; an "preview ruleset" view in the admin UI before apply.
- **R-021** — Operators familiar with ufw will reach for it instinctively and undo the masking. Mitigation: document loudly in [README.md](../../README.md) and in the systemd unit description that ufw is masked deliberately.
- **R-022** — SSH and the Islandr HTTPS port living outside the `islandr` table means there are now *two* firewall surfaces on the hub VM. An operator could mis-edit the input table and break SSH. Mitigation: ship a documented "minimal input table" template alongside Islandr; deployment guide spells out the boundary.
- **R-023** — Distros without nftables (older systems, niche derivatives) are unsupported. Mitigation: state the minimum distro version in [docs/prd.md](../prd.md) N-02.

**Accepted trade-offs**

- Higher barrier to entry for operators who only know ufw/iptables. They need to learn enough nftables to read the generated ruleset and the separate input table.
- Recompute-and-replace is a heavier operation than incremental updates, but at this scale (≤ a few hundred peers per hub) the apply takes milliseconds.

## References

- [nftables wiki — atomic ruleset replacement](https://wiki.nftables.org/wiki-nftables/index.php/Atomic_rule_replacement)
- [nft(8) — `-c` check mode](https://www.netfilter.org/projects/nftables/manpage.html)
- [docs/prd.md](../prd.md) — F-08 (atomic reload requirement), N-02 (Linux distro support)
- [docs/adr/0005-hub-only-firewall.md](0005-hub-only-firewall.md) — what the firewall scope is and is not
