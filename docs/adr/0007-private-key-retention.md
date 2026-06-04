# ADR-0007 — Private-key retention policy (instance-wide, two modes in v1)

**Status:** Accepted
**Date:** 2026-05-30
**Deciders:** Christian Cohnen

## Context

WireGuard peers are identified by a Curve25519 keypair. The private key lives on the *client* device — that's the whole security model. Islandr generates the keypair server-side for the user's convenience (so they don't have to install `wg` themselves), then hands it back in the create-peer response.

The open question PRD OQ-1 originally raised: **does the server keep a copy of the private key, or forget it after the response?**

Two real-world reference points:

- **wg-easy, NetBird** — never persist. Lost QR ⇒ regenerate peer.
- **PiVPN** — persists every peer's full `.conf` (including the private key) in `/etc/wireguard/configs/`. `pivpn -qr` re-renders the QR anytime. Operator convenience over strict key hygiene.

Both choices are defensible for their target audience. PiVPN's audience is the single-operator homelab where "the server has all secrets anyway" is true; the hub VM holds the WireGuard server's *own* private key, which is more sensitive than any individual peer's. Forbidding peer-key retention while keeping the server key on disk is closing the smallest door in the room.

For Islandr's audience profile (PRD §3 — Felix the sysadmin running a 30–80 person company *and* the homelab operator) we have both worlds. A one-size-fits-all "never persist" is too strict for the homelab; a one-size-fits-all "always persist" is too loose for the company.

A first attempt at this ADR (in OQ-1's original resolution) picked `never` as the only mode. After feedback this was reopened — operators familiar with PiVPN expect re-display to work, and the convenience is real.

## Decision

**Instance-wide config property `islandr.peer.privateKey.retention` with two values:**

- `never` (default) — server generates keypair, returns private key in the create-peer response body once, never persists it. No subsequent endpoint can return it. Recovery from lost artefact = regenerate peer.
- `plaintext` — server generates keypair, returns it in the create-peer response, **also** persists it in the `peers.private_key_pem` column unencrypted. `GET /api/v1/peers/{id}/conf` re-renders the `.conf` and QR on demand. PiVPN-equivalent behaviour. Audit-logged on every re-display.

**Not per-peer.** The mode is set once at the instance level and applies to every peer created from that point forward. Per-peer toggles invite "oops I clicked the wrong checkbox" leaks and make the threat model unfalsifiable ("which peers on this hub have keys server-side?" should have a single answer: all of them, or none).

**`encrypted` mode deferred to v2.** Adds master-key management (where does it live? env var? hardware token? Vault?), key rotation policy, decryption on every re-display. We don't have evidence that the operator wants the middle option enough to justify the complexity now. Easy to add later: extend the enum, fill the column with ciphertext instead of plaintext.

### Mode-specific behaviour

| Aspect | `never` | `plaintext` |
|---|---|---|
| `peers.private_key_pem` column | Always `NULL` | Populated on insert |
| `POST /users/{id}/peers` response | Includes `privateKey`, `conf`, `qrPngBase64` | Same |
| `GET /peers/{id}/conf` | `404 Not Found` (with explanation body) | `200` with `conf` + `qrPngBase64` |
| Audit on re-display | N/A | `PEER_CONF_RESHOW` entry, every call |
| Allowed in `prod` profile | Yes | Yes, but admin sees a banner: "Private keys are stored unencrypted." |

### Default rationale

`never` as the default — not because it's universally right, but because it's the *safer* misconfiguration. An operator who needed plaintext but got never gets a clean error (404 from re-display) and a one-line config fix. An operator who wanted never but got plaintext has been silently exposing keys without knowing it. The asymmetry says: ship the strict default, let comfort be opt-in.

## Alternatives considered (Pugh Matrix)

Baseline: **never + plaintext modes, instance-wide config** (the decision).

| Criterion (weight)                                   | never + plaintext (baseline) | never only (original OQ-1 answer) | plaintext only (PiVPN) | never + encrypted + plaintext (3 modes) | Per-peer toggle |
|------------------------------------------------------|------------------------------|------------------------------------|------------------------|------------------------------------------|-----------------|
| Strict N-06 alignment when chosen (4)                | 0                            | +1                                 | -1                     | 0                                        | 0               |
| PiVPN-style re-display convenience when chosen (4)   | 0                            | -1                                 | +1                     | 0                                        | 0               |
| Single instance-wide invariant (audit clarity) (4)   | 0                            | +1                                 | +1                     | 0                                        | -1              |
| Time-to-v1 (3)                                       | 0                            | +1                                 | +1                     | -1                                       | -1              |
| Master-key infra cost (3)                            | 0                            | 0                                  | 0                      | -1                                       | 0               |
| Misconfiguration blast radius (operator picks wrong) (3) | 0                        | 0                                  | -1                     | 0                                        | -1              |
| Migration path to v2 `encrypted` (2)                 | 0                            | -1                                 | -1                     | n/a                                      | 0               |
| Operator familiarity (PiVPN expectation) (2)         | 0                            | -1                                 | +1                     | 0                                        | 0               |
| **Weighted total**                                   | **0**                        | **+2**                             | **+2**                 | **−6**                                   | **−8**          |

Honest reading:

- **`never` only** and **`plaintext` only** both score +2 because each is strictly better than the baseline on its own native axis (strict security vs. PiVPN convenience). They lose on the other axis. The baseline is a *deliberate compromise* — neither single-mode answer is wrong, they're answers for different operators. Picking the baseline means "let the operator decide once per instance."
- **Three modes (`never + encrypted + plaintext`)** scores −6 mostly on `encrypted`'s implementation cost. The encrypted path is genuinely valuable for compliance-bound operators who want neither "in the clear on disk" nor "regenerate on every loss." We chose to ship the simpler shape first and add `encrypted` when an actual operator demand surfaces — additive change, won't paint us into a corner.
- **Per-peer toggle** is the worst choice on the matrix. Audit-clarity collapses (you can no longer say "this hub stores no keys"), and misconfiguration becomes high-blast-radius (one wrong checkbox per peer). Rejected.

## Consequences

**Positive**

- Operators who want strict no-server-side-keys get exactly that, by default.
- Operators who want PiVPN ergonomics get them with one config-property change, no code.
- Threat model on a given hub is one-line answerable: "is `islandr.peer.privateKey.retention=plaintext`? then yes, keys are on disk. otherwise no."
- Adding `encrypted` later is purely additive — new enum value, new column-content interpretation, no schema break.

**Risks created**

- **R-060** — An operator in `plaintext` mode who forgets that setting and treats the hub as low-secret invites exactly the leak this ADR documents. Mitigation: Admin Console shows a permanent banner "Private keys are stored unencrypted on this hub" in `plaintext` mode. Startup log line. Settings page reads the value back to the admin in plain words, not just config-string form.
- **R-061** — A change of mode from `plaintext` → `never` leaves existing rows with key material in the DB. Mitigation: a one-shot CLI / admin endpoint `purge-private-keys` that NULLs the column for all rows. Documented in the operations guide. Audit entry per row.
- **R-062** — Backup files (SQLite `.db` snapshots, Postgres dumps) in `plaintext` mode contain every peer's private key. Operators backing up off-host now own that exposure. Mitigation: documented loudly in [README.md](../../README.md); the `plaintext` banner repeats this point.
- **R-063** — `GET /peers/{id}/conf` is a re-display endpoint; if an attacker compromises a session cookie they get every peer's `.conf`. Mitigation: same auth requirements as the create endpoint; in v2 the system-role check restricts this to `ADMIN`s (END_USERs can only re-display their *own* peers via `/me/peers/{id}/conf`).
- **R-064** — Audit log of `PEER_CONF_RESHOW` events grows quickly if used in scripts. Acceptable: the audit table is append-only and the retention story already exists ([F-15](../prd.md#5-scope--functional-requirements)).

**Accepted trade-offs**

- The middle ground (encrypted-at-rest) doesn't exist in v1. Operators with that compliance shape will have to wait for v2 or use the strict default.
- `plaintext` mode is genuinely less secure than `never`. We name that out loud and let the operator make the call.
- Mode is global per instance — operators with mixed needs (some users strict, some not) need two Islandr instances. We think this is rare enough at v1 scale to not block on.

## References

- [WireGuard Whitepaper §4 — key generation and rotation](https://www.wireguard.com/papers/wireguard.pdf)
- [PiVPN `pivpn-qr` command](https://docs.pivpn.io/wireguard/) — the reference for the `plaintext` mode behaviour
- [docs/prd.md §5 — F-03](../prd.md#5-scope--functional-requirements) — functional requirement, lists both modes
- [docs/prd.md §11 — OQ-1](../prd.md#11-open-questions) — resolution pointer
- [docs/adr/0006-resource-level-acl.md](0006-resource-level-acl.md) — same trust-boundary reasoning (hub is internet-exposed, accept it, give the operator a knob)
