# Reaching the internal zone from a client

The built-in resolver answers for your managed zone and forwards everything
else to its own configured upstream. What remains is telling the client to ask
it — and on a split tunnel that is less obvious than it looks.

## The one rule that matters

**On the official WireGuard app with a split tunnel, the DNS field (Settings →
Network) must contain the hub's IP and nothing else.**

List a public fallback beside it and ordinary hostname lookups — `ping`, a
browser — fail silently for the internal zone. The app installs the whole entry
as merely interface-scoped, and the system never asks it for names it does not
believe belong to that interface. A direct query keeps working the whole time,
which is what makes this hard to diagnose:

```bash
dig @10.0.0.1 fileserver.cologne-office.islandr.internal   # answers
ping fileserver.cologne-office.islandr.internal            # does not resolve
```

Confirmed against a live install. The fallback was never needed in the first
place: the resolver already forwards anything outside the zone upstream, so
removing it costs nothing.

Search domains are a separate mechanism and fine to keep. They only save you
typing a bare hostname instead of the full name.

## If you would rather not think about it

**Route everything through the hub.** On a full tunnel the hub's resolver
becomes the system default and none of the above applies — no DNS field to get
right, no scoping rules to reason about.

## Per-platform alternatives

These matter when you want the internal zone resolvable *alongside* other DNS
servers, rather than instead of them.

| Client | What it offers |
|---|---|
| `wg-quick` on Linux | A `~domain` suffix in the `DNS =` line gives real per-domain routing, even with other servers configured. |
| macOS, Linux | A file at `/etc/resolver/<zone>` containing `nameserver <hub-ip>` routes that zone only. Needs root once; survives reconnects. |
| Third-party apps | Some (Passepartout, for example) support the `~domain` form too, usually in a paid tier. |

## When a name does not resolve

Check in this order — it is roughly cheapest-first, and each step rules out the
one below it.

1. **Is the resolver running?** The DNS page reports `Active`, the address it
   listens on, and how many names it can answer. A hub that reports
   *Enabled, not running* usually failed to bind port 53 — the service needs
   `CAP_NET_BIND_SERVICE`, which [`setup-hub.sh`](setup-hub.sh) grants.
2. **Does the name exist?** The same page lists every resolvable name, spelled
   as a peer would have to query it. A resource without a DNS name set under
   *Resources* is not in that list.
3. **Does a direct query answer?** `dig @<hub-ip> <name>` bypasses the client's
   own resolution rules. If this answers and `ping` does not, you are looking
   at the split-tunnel scoping problem above, not at the hub.
4. **Is the peer allowed to reach it?** A real query from a peer is
   ACL-filtered. The *Try a lookup* box on the DNS page can run the same query
   as a chosen peer, which tells apart "no such name" from "not for you".
