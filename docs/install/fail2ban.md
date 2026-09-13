# Blocking repeated login attempts

Islandr slows down guessing on its own, and can hand the job of blocking to
fail2ban on top. The two layers are independent, and the first one matters
more: it works on every deployment, including the ones with no fail2ban, no
root and no journald.

## What Islandr does by itself

Every failed local login is counted twice — once against the account, once
against the source address — and the slower of the two counters decides how
long the next attempt is held before it is answered. The delay doubles per
failure past the first two and stops at five seconds. Success clears both
counters, and fifteen quiet minutes forget them.

It is a delay, not a lockout, and deliberately so. An account that can be
locked out is an account anyone who knows its address can deny; the delay costs
an attacker time and costs the legitimate user, who mistypes a password twice
and then gets in, almost nothing.

Two counters rather than one, because they catch different attacks: the account
counter catches a focused attempt on one login, and the address counter catches
password spraying, where every account is tried once or twice and a per-account
counter would never fire.

There is also a ceiling on login attempts in flight at once — without it, a
thousand parallel requests each sleeping a second would be no cost to the
attacker and a real one to the hub. Attempts over the ceiling are answered
`429` with a `Retry-After`.

All of it is tunable, and the defaults are in `application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `islandr.auth.throttle.free-attempts` | `2` | Failures answered at full speed before the delay starts. |
| `islandr.auth.throttle.base-delay-ms` | `250` | The first delay; doubles per further failure. |
| `islandr.auth.throttle.max-delay-ms` | `5000` | Ceiling on the delay. |
| `islandr.auth.throttle.window-minutes` | `15` | Quiet time after which a counter is forgotten. |
| `islandr.auth.throttle.max-concurrent` | `8` | Login attempts in flight at once. |

The two proxy-related values are **not** here — they live in Settings, see
below, because a wrong entry there should be fixable without a restart.

The counters live in memory, in the one Islandr process, and are lost on
restart. Persisting them would add a write path whose rate an attacker
controls.

## The log line fail2ban matches

No log *file* is needed. fail2ban reads journald directly, which is what a
default Islandr install already feeds through stdout. What matters is that the
line names the address:

```
login failed user=bob@firma.de ip=203.0.113.9
```

That shape is part of the interface. It is not rewritten without a release note.

### Filter

`/etc/fail2ban/filter.d/islandr.conf`:

```ini
[Definition]
failregex = ^.*login failed user=.* ip=<HOST>\s*$
ignoreregex =
```

### Jail

`/etc/fail2ban/jail.d/islandr.conf`:

```ini
[islandr]
enabled  = true
backend  = systemd
journalmatch = _SYSTEMD_UNIT=islandr.service
filter   = islandr
maxretry = 5
findtime = 10m
bantime  = 1h
action   = nftables[type=allports]
```

### Checking that it actually fires

Do not assume the regex matches. Ask:

```bash
fail2ban-regex systemd-journal /etc/fail2ban/filter.d/islandr.conf --journalmatch "_SYSTEMD_UNIT=islandr.service"
sudo fail2ban-client status islandr
```

The first command prints how many lines the filter matched; if it is zero after
a deliberately failed login, the jail is doing nothing.

## The trap: banning your own proxy

A hub behind Cloudflare — or nginx, Traefik, Caddy — sees the proxy's address
as the peer address on every single request. Ban on that and you ban the proxy,
which is to say everyone including yourself, while the attacker carries on
unaffected.

The fix is not to trust `X-Forwarded-For` either. Anyone can send it. Trusting
it unconditionally turns the ban mechanism into a weapon: an attacker evades
their own ban by rotating the header, and can get an arbitrary third party — or
your own office address — banned instead.

**So Islandr reads the header only when the request actually came from a proxy
you named**, and the list is empty by default, which means an unproxied install
cannot be fooled by a header at all.

Set it in the Admin Console under **Settings → Reverse proxy**:

| Field | What goes in it |
|---|---|
| Trusted proxies | Your proxy's address as Islandr sees it, or the edge network's ranges. CIDRs or bare addresses, comma-separated. |
| Client address header | Empty for `X-Forwarded-For`. Behind Cloudflare, `CF-Connecting-IP`. |

It takes effect immediately — no restart.

To set it at install time instead, `setup-hub.sh` takes `TRUSTED_PROXIES=` and
`CLIENT_IP_HEADER=`, so the very first failed login already logs an address
worth acting on. The precedence is one sentence: **a value set in the console
wins; while that field is empty, `/etc/default/islandr` applies — at every
start, not only the first.**

Two consequences worth knowing:

- You can still correct the environment file later, as long as nobody has typed
  a value into the console.
- Clearing the field in the console does **not** survive a restart while
  `ISLANDR_AUTH_TRUSTED_PROXIES` is still set — "empty" is exactly the state
  that lets the default back in. Removed your proxy? Clear both. The console
  warns about this when it sees the field empty and the variable set.

**Use the address the log already shows you.** A failed login prints the peer
Islandr actually sees, which is exactly the value to trust — no guessing
whether the proxy reaches you over loopback, a bridge or a container network.

Islandr walks the forwarded chain from the right and takes the first address
the trusted hops did not vouch for, so a forged prefix in the header is ignored.

### Traefik

Traefik appends to `X-Forwarded-For` rather than replacing it, which is what
the right-to-left walk above expects. Running on the same host and proxying to
Islandr on loopback, the log line reads `ip=127.0.0.1` until you trust it:

```
Trusted proxies:        127.0.0.1, ::1
Client address header:  (empty — X-Forwarded-For)
```

**With Cloudflare in front of Traefik this is not enough.** Islandr then sees
`X-Forwarded-For: <client>, <cloudflare-edge>` — Traefik appended the address
*it* saw, and that is Cloudflare. Trusting only loopback would make the
Cloudflare edge the address that gets banned. Use Cloudflare's own header
instead, which carries one address rather than a chain:

```
Trusted proxies:        127.0.0.1, ::1
Client address header:  CF-Connecting-IP
```

That only holds as long as nobody can reach Traefik directly — restrict the
origin to Cloudflare's ranges at the firewall, or anyone bypassing the edge can
invent a `CF-Connecting-IP` and Traefik will pass it straight through.

> This is a different question from `quarkus.http.proxy.*` in the same file.
> Those decide how OIDC redirect URIs are built — getting a URL right and
> deciding whom to ban do not deserve the same level of trust, and only the
> second one is settled by `islandr.auth.trusted-proxies`.

### If you are on Cloudflare, block at the edge instead

The origin never sees the attacker directly, so a ban there stops requests that
have already crossed the network. A Cloudflare WAF rate-limiting rule on
`/api/v1/auth/login` is both more effective and cheaper. Use fail2ban on the
origin for the case where someone reaches it without going through the edge.

## What this does not cover

- **OIDC logins.** The identity provider owns those; Islandr never sees the
  password. Entra ID and Google both have their own lockout policies.
- **A second factor.** Making guessing expensive is not the same as making it
  useless — see [ADR-0028](../adr/0028-webauthn-library-and-integration.md).
- **Shipping logs anywhere.** No syslog forwarding, no SIEM integration.

## Related

- [hardening.md](hardening.md) — the rest of the hub hardening
- [reverse-proxy.md](reverse-proxy.md) — running Islandr behind a proxy
