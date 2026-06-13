# 3. System Scope and Context

## 3.1 Business Context

### C4 Level 1 — System Context

![System Context Diagram](../../architecture/diagrams/SystemContext.png)

> Diagram generated from [`architecture/workspace.dsl`](../../architecture/workspace.dsl) on every push.

### Communication partners

| Partner | Direction | Protocol | Purpose |
|---|---|---|---|
| Admin (Felix) | → Islandr | HTTPS via reverse proxy | Admin Console: peer management, ACL matrix, user/role administration, audit log. |
| End User (Lena) | → Islandr | HTTPS via reverse proxy | Self-Service Portal: enroll devices, view own access list. |
| OIDC Provider | ← Islandr | HTTPS | Islandr fetches JWKS and verifies ID tokens for Microsoft 365 / Google users. Authorization Code Flow — redirect goes through the user's browser, not a server-to-server call. |
| WireGuard | ← Islandr | Shell (wg CLI) | Islandr calls `wg set wg0 peer …` to add/update peers and `wg show wg0 dump` to poll activity. |
| nftables | ← Islandr | Shell (nft CLI) | Islandr writes a ruleset file, validates it with `nft -c -f`, and applies it atomically with `nft -f`. |
| Reverse Proxy *(optional)* | ↔ Islandr | HTTP | Operator-provided. Terminates TLS, forwards `/api/v1/*` to Islandr backend, serves static frontend files. Islandr can be reached directly on port 8080 without one (e.g. in development or behind an existing TLS terminator). Not a managed component of Islandr — therefore not a building block in Chapter 5. |

## 3.2 Technical Context

Islandr exposes one interface: a REST/JSON API on HTTP (port 8080 by default, behind the reverse proxy). There is no WebSocket, no message queue, and no gRPC endpoint in v1.

```
Browser → HTTPS :443 → [Reverse Proxy] → HTTP :8080 → Islandr Backend
                                       ↗
           Static files (Admin Console, Self-Service Portal)

           (Reverse proxy is optional; direct access on :8080 is also supported.)
```

The two shell integrations (`wg`, `nft`) are synchronous, blocking calls via `java.lang.ProcessBuilder`. They run on Quarkus's worker thread pool, not the I/O thread. Execution time is bounded: `wg show dump` completes in milliseconds; `nft -f` completes in under 1 second for typical rulesets.

The OIDC integration is stateless on the server side: Islandr fetches the JWKS endpoint of each configured provider, caches public keys, and verifies incoming ID tokens locally. No session is maintained with the OIDC provider after login.

### What is not in scope

- UCG API access — the hub VM holds no UCG credentials and makes no calls into the internal network (see [ADR-0005](../adr/0005-hub-only-firewall.md)).
- Email / SMTP — no magic links in v1. Authentication is OIDC or local password.
- Push notifications — the frontend polls for status updates; there is no server-sent event or WebSocket channel in v1.
- Multi-hub / mesh topology — v1 is single-hub.
