# TLS: built-in vs. reverse proxy

Islandr can terminate HTTPS itself (ADR-0015, ADR-0019) — no reverse proxy required. That's the
default in `setup-hub.sh` and `docker-compose.yml`. It is not the *only* supported way. This page
lays out both paths side by side so you can pick the one that fits your deployment, not just the
one the scripts default to.

## Quick decision

| Your situation | Use |
|---|---|
| Public VM, DNS points straight at it, no existing proxy/CDN | **Built-in TLS + ACME** — zero extra moving parts |
| Already behind Cloudflare, CloudFront, Fastly, Azure Front Door, or similar CDN | **Built-in TLS, Referenced or Managed mode**, with the CDN's own edge/origin certificate — the CDN already terminates TLS at the edge, and CDN-fronted origins usually can't expose port 80 for ACME's HTTP-01 challenge |
| You already run Caddy/Traefik/nginx on this host for other services | **Reverse proxy** — one less thing to duplicate |
| You want a single log/access point in front of several internal apps, not just Islandr | **Reverse proxy** |

Both paths are first-class. Nothing about the "not the right tool" list in the README changes
based on which one you pick.

## Path A — Built-in TLS (no reverse proxy)

Islandr's HTTP listener binds directly to ports 80 and 443 (`QUARKUS_HTTP_PORT` /
`QUARKUS_HTTP_SSL_PORT`) and terminates TLS itself via Quarkus's TLS registry. Three certificate
sources are available in **Settings → TLS**, and switching between them is a live reload, not a
restart:

1. **ACME (Let's Encrypt)** — the default to reach for. Set a domain in Settings, and Islandr
   requests, installs, and auto-renews a real certificate on its own (a daily check, plus one on
   every boot as a backstop). Requires port 80 reachable from the public internet — Let's
   Encrypt's HTTP-01 challenge validates on port 80 specifically; this is fixed by RFC 8555, not
   configurable on either side. Port 80 must stay open even after a certificate is issued, so
   renewal keeps working.
2. **Managed (upload)** — paste or upload a `.p12` or PEM cert + key (e.g. a Cloudflare Origin
   Certificate, or one issued by an external ACME client) via Settings. Stored in the database,
   encrypted at rest when `ISLANDR_ENCRYPTION_KEY` is configured (same mechanism as private-key
   retention, ADR-0007).
3. **Referenced (file path)** — point Settings at a keystore file on disk that some other process
   manages (your own `certbot` timer, a Docker secret, a CDN's origin-cert delivery tooling).
   Islandr never touches or copies it; it just watches the file and reloads when it changes — the
   same trust boundary a reverse proxy's own certificate file already has today.

`setup-hub.sh` and `docker-compose.yml` set this up out of the box: `QUARKUS_HTTP_HOST=0.0.0.0`,
port 80 for HTTP, port 443 for HTTPS, plus (native only) `AmbientCapabilities=CAP_NET_BIND_SERVICE`
in the systemd unit so the unprivileged `islandr` user can bind those two ports without running as
root.

**Firewall / security group:** open 80 and 443 to the public internet. Port 80 is not just a
bootstrap step — leave it open permanently if you're using ACME.

## Path B — Reverse proxy

Bind Islandr to loopback on non-privileged ports instead, and let a proxy on the same host (or a
CDN) terminate TLS.

**Native binary** — edit `/etc/default/islandr`:

```
QUARKUS_HTTP_HOST=127.0.0.1
QUARKUS_HTTP_PORT=8080
QUARKUS_HTTP_SSL_PORT=8443
```

then `sudo systemctl restart islandr`.

**Docker Compose** — restrict the port mapping to loopback instead of publishing 80/443:

```yaml
ports:
  - "127.0.0.1:7080:8080"
  - "127.0.0.1:7443:8443"
```

**Caddy** (simplest — automatic Let's Encrypt on the proxy's own side):

```
islandr.yourdomain.com {
    reverse_proxy 127.0.0.1:7080
}
```

**nginx:**

```nginx
server {
    listen 443 ssl;
    server_name islandr.yourdomain.com;
    ssl_certificate     /etc/ssl/certs/islandr.crt;
    ssl_certificate_key /etc/ssl/private/islandr.key;
    location / { proxy_pass http://127.0.0.1:7080; }
}
```

**Traefik** (dynamic file provider — drop this next to your `traefik.yml`, or translate to the
equivalent Docker labels if Traefik discovers containers instead of files):

```yaml
# dynamic.yml
http:
  routers:
    islandr:
      rule: "Host(`islandr.yourdomain.com`)"
      entryPoints:
        - websecure
      service: islandr
      tls:
        certResolver: le   # your ACME resolver, defined elsewhere in traefik.yml

  services:
    islandr:
      loadBalancer:
        servers:
          - url: "http://127.0.0.1:7080"
```

Islandr already trusts `X-Forwarded-Proto`/`X-Forwarded-Host`/`X-Forwarded-Prefix`
(`quarkus.http.proxy.*` in `application.properties`) — Traefik, Caddy, and nginx's `proxy_pass`
all set these by default, so no extra header wiring is needed on the app side.

With a reverse proxy in front, Islandr's own built-in TLS goes unused — plain HTTP on the loopback
port is fine, since the proxy is the only thing that talks to it directly.

### Cloudflare specifics

If Cloudflare sits in front (orange-clouded DNS, with or without a reverse proxy behind it):

- **DNS record**: an `A`/`AAAA` record for `islandr.yourdomain.com` pointing at the origin's
  public IP, proxy status **on** (orange cloud) — this is what puts Cloudflare's edge, not the
  origin, in the TLS path the browser talks to.
- **SSL/TLS mode**: **Full (strict)** under SSL/TLS → Overview, not Flexible. Flexible terminates
  TLS at Cloudflare's edge and speaks plain HTTP to the origin from there — the edge-to-browser
  leg is still encrypted, but the edge-to-origin leg isn't, which defeats the point of running TLS
  at all. Full (strict) needs a certificate at the origin that Cloudflare can validate: either a
  **Cloudflare Origin Certificate** loaded via Path A's Managed mode (Settings → TLS), or a real
  cert from ACME/your reverse proxy.
- **Cookie-Secure rewrite**: Islandr's session cookie (`AuthResource.buildCookie`) intentionally
  leaves the `Secure` flag unset at the app level — dev runs over plain HTTP, and the app has no
  reliable way to know on its own that something in front of it is serving HTTPS. With Cloudflare
  proxying, the browser-facing connection is HTTPS, so add a **Transform Rule** (Rules → Transform
  Rules → Modify Response Header → Add) that appends `; Secure` to the `Set-Cookie` header for
  `islandr.yourdomain.com`. Without it the cookie still functions, but nothing stops it from being
  replayed over a plain-HTTP request if one ever reaches the origin — the rewrite is what actually
  enforces HTTPS-only for the session.

## Trade-offs

| | Built-in TLS | Reverse proxy |
|---|---|---|
| Extra process to run and update | None | Caddy/Traefik/nginx |
| Certificate lives in | Islandr's DB (managed) or a file Islandr watches (referenced/ACME) | The proxy's own config |
| Renewal | Automatic (ACME mode) or your own tooling (referenced mode) | Whatever the proxy handles |
| Works behind a CDN that already terminates TLS | Yes (Referenced/Managed with the CDN's edge cert) | Yes |
| Single front door for multiple apps on one host | No — Islandr is the only thing on 80/443 | Yes |
| Native-image / systemd capability needed | `CAP_NET_BIND_SERVICE` for ports 80/443 | None (proxy runs as its own user, typically already has it) |

Neither path is more "production-ready" than the other — pick based on what's already running on
the host, not on which one this doc lists first.

## References

- [ADR-0015](../adr/0015-builtin-tls-termination.md) — built-in TLS termination, the
  managed/referenced certificate modes
- [ADR-0019](../adr/0019-acme-hand-rolled-client.md) — the ACME/Let's Encrypt client, HTTP-01
  challenge, renewal scheduling
- [install.md](../install.md) — full native-binary + systemd install walkthrough
