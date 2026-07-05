# Onboarding fixes + local user passwords (design)

**Date:** 2026-07-05
**Status:** Approved (design) — implementation pending
**Branch:** `fix/onboarding-and-port-defaults` (off `main` @ 0.10.0)
**Target version:** 0.10.0 (ships alongside the IronRDP feature; independent of the socket-proxy 0.11.0 line)

## 1. Problem & scope

Four issues surfaced while using 0.9.4/0.10.0 on a fresh install. They share one
theme — a new operator cannot get productive without hidden knowledge:

1. **Port form gives no default port per protocol.** Selecting RDP/SSH/HTTP/… still
   leaves the port field empty; the operator must know 3389/22/80/… by heart.
2. **Networks: gateway-peer selector is silently empty** when no eligible peer
   exists — no hint that a site peer must be created first.
3. **Fresh install has no usable identity.** The ENV-admin login has `userId=null`
   (it is not a `User` row), so the admin cannot own a peer or self-assign roles,
   and client-peer creation fails with no explanation that users are required.
4. **No local users without an external IdM.** Users authenticate only via OIDC
   (Google/Microsoft) or the single ENV-admin. You cannot create a local user with
   a password.

In scope: all four, on one branch. Fixes 1, 2, 3b are frontend-only. Fix 3a and
Fix 4 are backend (TDD).

Out of scope (existing TODO items, not touched): email-invite onboarding,
self-service password change, password complexity policy beyond a minimum length,
login rate-limiting / lockout.

## 2. Fix 1 — Port default by protocol (frontend)

In the resource port form ([ResourcesView.js](../../../src/main/resources/META-INF/resources/js/views/ResourcesView.js), protocol `<select>`), add a
protocol→default-port map and fill `portForm.port` on protocol change:

| RDP | VNC | SSH | SFTP | HTTP | HTTPS | SMB | PRINT | X11 |
|----|----|----|----|----|----|----|----|----|
| 3389 | 5900 | 22 | 22 | 80 | 443 | 445 | 631 | 6000 |

**Fill rule:** on protocol change, set the port to the new default **when the field
is empty or still holds the previous protocol's default** — a hand-typed custom
port is preserved. The user can always overwrite. Frontend-only.

**Acceptance:** selecting RDP fills 3389; switching to SSH changes it to 22; after
typing 8443 manually, switching protocol leaves 8443 untouched.

## 3. Fix 2 — Gateway-peer hint (frontend)

In the network (site) form, the gateway-peer selector: when no eligible peer
exists, render a hint instead of an empty dropdown — "Kein Peer verfügbar — lege
zuerst einen Site-Peer an", linking to Peers. de/en. Frontend-only.

**Acceptance:** with zero site peers, the network form shows the hint, not an empty
select; with ≥1 eligible peer, the select renders normally.

## 4. Fix 3 — Onboarding identity + no-user peer UX

### 4a. Seed admin User + bind ENV-admin login (backend, TDD)

New startup bean `AdminUserBootstrap` (`@Observes StartupEvent`). When the
ENV-admin is enabled ([AdminBootstrap](../../../src/main/java/de/chriscohnen/islandr/auth/AdminBootstrap.java) `isEnabled()`) and no `User` with email
`admin@local` exists, create:

```
User(name = ISLANDR_ADMIN_USER (default "admin"), email = "admin@local",
     isAdmin = true, provider = local)
```

Idempotent — only creates if missing. The local login in
[AuthResource](../../../src/main/java/de/chriscohnen/islandr/auth/AuthResource.java) resolves the session's `userId` to this user (lookup by email
`admin@local`); if the row is absent, it falls back to today's `userId=null`.

**Acceptance:** on a fresh DB with `ISLANDR_ADMIN_PASSWORD` set, boot creates one
`admin@local` user; `GET /auth/me` after local login returns that user's `userId`;
a second boot creates no duplicate.

### 4b. No-user peer UX (frontend)

With 4a there is always ≥1 user on a fresh install, so the core failure is gone.
As a safety net for the zero-user state (e.g. all users deleted): in the peer
modal, when the user list is empty, disable `type=client` (greyed with hint "Erst
Benutzer anlegen") while `type=site` stays selectable. de/en.

## 5. Fix 4 — Local users with passwords (backend + frontend, TDD)

### Storage
Add `password_hash` (nullable VARCHAR) to `users` (Flyway migration). Hash with
**PBKDF2WithHmacSHA256** (JDK, no dependency): per-user random 16-byte salt,
210 000 iterations, 256-bit derived key, stored PHC-style:
`pbkdf2$sha256$<iterations>$<base64-salt>$<base64-hash>`. A `null` hash means the
user has no local password (OIDC/ENV only). The ENV-admin keeps its separate
in-memory SHA-256 ENV compare — unchanged, nothing persisted.

New `PasswordHasher` (pure, `crypto` package): `hash(raw)` → PHC string,
`verify(raw, phc)` → bool (constant-time compare on the derived key).

### Login
The local login form (username **or email** + password) resolves in order:
1. **ENV-admin** ([AdminBootstrap](../../../src/main/java/de/chriscohnen/islandr/auth/AdminBootstrap.java)) → session bound to the `admin@local` seed (4a).
2. else a **`User`** matched by email (or name) that has a `password_hash` →
   `PasswordHasher.verify` → session with that user's `userId` and `isAdmin`.
3. else 401.

OIDC buttons and the OIDC mutual-exclusion are untouched; local password auth
coexists with a configured OIDC provider.

### Admin UI (Users view)
Optional "Passwort setzen" on create/edit + a "Passwort zurücksetzen" action.
Setting a non-empty password (min length 8) stores its hash and enables local
login for that user; leaving it empty changes nothing. Passwords are **never
returned** by any endpoint. Set/reset writes an audit row
(`user.password_set` / `user.password_reset`, no password in the payload). de/en.

**Acceptance:** admin creates user `bob@firma.de` with password → Bob logs in via
the local form → `me.userId` is Bob's, `isAdmin=false`; wrong password → 401; the
ENV-admin still logs in; no endpoint ever echoes a password or its hash.

## 6. Security notes (Fix 4)

- PBKDF2 params (210k iter, SHA-256) are an OWASP-current baseline for the JDK KDF;
  the iteration count lives in the PHC string so it can be raised later without a
  migration.
- Timing: `verify` compares derived keys with `MessageDigest.isEqual`
  (constant-time). Unknown-user login still runs a dummy hash to avoid a
  user-enumeration timing oracle.
- No new login lockout/rate-limit here (pre-existing gap, tracked separately); the
  audit log already records `auth.login_local` failures.

## 7. Testing (TDD, traced)

- **Unit** — `PasswordHasher`: hash→verify round-trip; wrong password rejects;
  two hashes of the same password differ (random salt); a tampered PHC string
  rejects. `AdminUserBootstrap` seed logic (create-if-missing, idempotent).
- **Integration** (`@QuarkusTest`) — 4a: boot seeds one `admin@local`, local login
  sets `me.userId`, second boot no duplicate. Fix 4: create user with password →
  local login returns correct `userId`/`isAdmin`; wrong password → 401; ENV-admin
  still works; password never present in any response body; audit row written.
- Frontend fixes (1, 2, 3b) have no test harness — verified by inspection and a
  manual run.

## 8. Open points

- `admin@local` is a fixed placeholder email; if an operator later logs in via
  OIDC with a real email, that is a separate `User` — the local admin row remains
  the recovery identity. Acceptable.
- The seeded admin's `name` follows `ISLANDR_ADMIN_USER`; a later rename of that
  env var does not rename the row (idempotent seed only creates). Acceptable.
