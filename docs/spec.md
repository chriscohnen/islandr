# Specification Supplement

Complements `prd.md` (personas, EARS requirements, domain model, happy-path flows) and `docs/arc42/` (architecture). This document covers what both lack: Business Rules with source locations, the Peer state machine, and Use Case extensions for error paths.

---

## 1. Business Rules

Each rule carries an ID used in test names and comments (`// BR-004`).

### 1.1 Peer creation and update

| ID | Rule | HTTP status on violation | Implemented in |
|---|---|---|---|
| BR-001 | `name` must not be blank | 400 | `PeerDto.CreateRequest:@NotBlank` |
| BR-002 | `assignedIp` must be a syntactically valid IPv4 or IPv6 address | 400 | `PeerDto.CreateRequest:@ValidIpAddress` → `IpAddressValidator` |
| BR-003 | `assignedIp` must be within `settings.wgSubnet` | 400 | `PeerService.validateAssignedIp()` → `IpSubnet.contains()` |
| BR-004 | `assignedIp` must not already be assigned to another peer (enabled or disabled) | 409 | `PeerService.validateAssignedIp()` → `Peer.count("assignedIp = ?1", ip)` |
| BR-005 | `assignedIpv6`, if provided, must be within `settings.wgSubnet6` | 400 | `PeerService.validateAssignedIpv6()` |
| BR-006 | `assignedIpv6` requires `settings.wgSubnet6` to be configured | 400 | `PeerService.validateAssignedIpv6()` |
| BR-007 | `publicKey` / `privateKey`, if provided, must be a 44-char Base64 WireGuard key | 400 | `PeerDto.CreateRequest:@Pattern(regexp = "^$\|^[A-Za-z0-9+/]{43}=$")` |
| BR-008 | When both `publicKey` and `privateKey` are provided, the derived public key must match the supplied `publicKey` | 400 | `PeerService.createForUser()` via `wg.derivePublicKey()` |
| BR-009 | Supplying `privateKey` without `publicKey` is rejected | 400 | `PeerService.createForUser()` |
| BR-010 | `type` must be `client` or `site`; defaults to `client` when omitted | 400 | `PeerDto:@Pattern` + `PeerDto.resolvedType()` |
| BR-011 | `siteAllowedCidrs` is only meaningful for `type=site` peers | 400 | `PeerService.createForUser()` |
| BR-012 | `deviceType`, if provided, must be one of: `laptop`, `desktop`, `mobile`, `tablet`, `server`, `other` | 400 | `PeerDto:@Pattern` |
| BR-013 | `mtu`, if provided on update, must be 576–65535 | 400 | `PeerDto.UpdateRequest:@Min(576) @Max(65535)` |

### 1.2 Site peer CIDR validation

All checks run in `PeerService.validateSiteCidrs()`.

| ID | Rule | HTTP status on violation | Implemented in |
|---|---|---|---|
| BR-014 | Each entry in `siteAllowedCidrs` must be a valid CIDR | 400 | `IpSubnet.parse()` |
| BR-015 | Site CIDRs must not overlap `settings.wgSubnet` | 400 | `IpSubnet.overlaps(wg)` |
| BR-016 | CIDRs within the same `siteAllowedCidrs` list must not overlap each other | 400 | pairwise `IpSubnet.overlaps()` |
| BR-017 | Site CIDRs must not overlap CIDRs already declared on any other site peer | 400 | `Peer.list("type = ?1", "site")` → pairwise `IpSubnet.overlaps()` |

### 1.3 ACL resources and ports

| ID | Rule | HTTP status on violation | Implemented in |
|---|---|---|---|
| BR-018 | Resource IP must be a valid IP address | 400 | `ResourceDto:@ValidIpAddress` |
| BR-019 | Resource port must be 0–65535 | 400 | `ResourceDto:@Min(0) @Max(65535)` |
| BR-020 | Resource transport must be `tcp`, `udp`, or `both` | 400 | `ResourceDto:@Pattern(regexp = "^(tcp\|udp\|both)$")` |

### 1.4 Private key retention

| ID | Rule | HTTP status on violation | Implemented in |
|---|---|---|---|
| BR-021 | Retention mode `never` (default): the private key is never written to the database | — | `PeerService.createForUser()`: `peer.privateKeyPem` stays null |
| BR-022 | Retention mode `encrypted`: `EncryptionService` must be configured (key loaded); if not, creation fails | 500 | `PeerService.createForUser()` → `encSvc.isConfigured()` |
| BR-023 | The private key is returned in the creation response exactly once; it is absent from all subsequent reads | — | `PeerDto.CreateResponse` / `PeerDto.Response.from()` |

### 1.5 Firewall

| ID | Rule | HTTP status on violation | Implemented in |
|---|---|---|---|
| BR-024 | The nftables ruleset is validated with `nft -c -f` before being applied; on validation failure the previous active ruleset stays unchanged and `FirewallState.lastStatus` is set to `FAILED` | — (async state) | `RulesetService.recomputeAndApply()` → `adapter.validate()` |
| BR-025 | nftables apply is always a full ruleset replacement; partial updates are never issued | — | `RulesetService` design: `adapter.apply(snap.rulesetText())` |

### 1.6 Audit

| ID | Rule | HTTP status on violation | Implemented in |
|---|---|---|---|
| BR-026 | Every mutating API action writes an audit log entry before returning | — | `AuditService` called in each `*Resource.java` (not in `*Service.java`) |

### 1.7 Proxy enforcement availability (v2 — ADR-0012)

These rules govern the v2 Docker deployment, where enforcement runs through `islandr-proxy`. Source locations are planned, not yet implemented.

| ID | Rule | Status | Source location |
|----|------|--------|-----------------|
| BR-027 | The container starts and serves the configuration plane (GUI, CRUD, JSON export/import) regardless of proxy-socket availability | — | v2 — socket-client adapter startup probe (planned) |
| BR-028 | While the proxy socket is absent or unreachable, an enforcing operation (`nft` apply, `wg set`) is persisted as **pending** and the config write still returns success | 200/201 | v2 — degraded adapter (planned) |
| BR-029 | The degraded adapter never reports a fake success for an enforcing operation; enforcement state stays `unavailable` until a proxy connects (closes R-122) | — | v2 — degraded adapter (planned) |
| BR-030 | On proxy (re)connect, pending configuration is reconciled via a full recompute and applied before enforcement state becomes `active` | — | v2 — reconcile-on-connect (planned) |
| BR-031 | Proxy availability is exposed via the API; while degraded the GUI shows an "enforcement unavailable" banner with install instructions | — | v2 — health endpoint + GUI banner (planned) |

---

## 2. Peer State Machine

A `Peer` entity has one boolean field, `enabled`. Combined with wg registration and nftables inclusion, the effective states are:

```
                         POST /users/{id}/peers
                                  │
                                  ▼
                            ┌─────────┐
                ┌──────────►│ enabled │◄───────────────────┐
                │           └────┬────┘                    │
   PUT enabled=true              │ PUT enabled=false        │
   (wg.setPeer)                  │ (wg.removePeer)          │
                │                ▼                          │
           ┌────┴────┐    ┌──────────┐   PUT enabled=true  │
           │ deleted │    │ disabled │ ──────────────────► (enabled)
           └─────────┘    └────┬─────┘
                               │ DELETE /peers/{id}
                               ▼
                          ┌─────────┐
                          │ deleted │
                          └─────────┘
```

**Transition guards and side-effects:**

| From | To | Trigger | Side-effects |
|---|---|---|---|
| — | enabled | `PeerService.createForUser()` | `wg.setPeer()` + `rulesets.recomputeAndApply()` (saga) + `audit.logCreate()` |
| enabled | disabled | `PeerService.setEnabled(false)` + `PeerResource.recomputeFromHook()` | `wg.removePeer()` + nftables recompute + `audit.logUpdate()` |
| disabled | enabled | `PeerService.setEnabled(true)` + `PeerResource.recomputeFromHook()` | `wg.setPeer()` + nftables recompute + `audit.logUpdate()` |
| enabled | deleted | `PeerService.delete()` + `PeerResource.recomputeFromHook()` | `wg.removePeer()` + nftables recompute + `audit.logDelete()` + hard-delete DB row |
| disabled | deleted | `PeerService.delete()` + `PeerResource.recomputeFromHook()` | `wg.removePeer()` (may warn if peer not in wg) + nftables recompute + `audit.logDelete()` + hard-delete DB row |

**Notes:**
- `PeerService.delete()` calls `wg.removePeer()` regardless of `enabled` state and logs a warning (not error) if it fails; the DB row is deleted either way.
- nftables recompute never throws: validation or apply failures set `FirewallState.lastStatus = FAILED` and log to audit, but do not abort the calling transaction.
- The audit log is the only durable record of deleted peers. The DB row is gone after deletion.

---

## 3. Use Cases (Cockburn Fully Dressed — error paths only)

The happy paths for UC-01/02/03 are in `docs/prd.md` §8. This section adds the extensions (error paths and alternatives).

### UC-01: Admin creates peer

**Primary Actor:** Admin  
**Trigger:** `POST /api/v1/users/{userId}/peers` or `POST /api/v1/peers`  
**PRD reference:** PRD §8 — Peer-Erstellung

**Extensions (numbered to match the main success scenario in PRD):**

- **1a.** Request body fails bean validation (BR-001/002/007/010/012) → 400; response body contains constraint violation details. No side-effect.
- **2a.** `assignedIp` outside `wgSubnet` (BR-003) → 400; `PeerService.validateAssignedIp()` throws `BadRequestException`.
- **2b.** `assignedIp` already taken (BR-004) → 409; error message includes the conflicting IP but not the other peer's name (limitation).
- **2c.** `siteAllowedCidrs` validation fails (BR-014..017) → 400; `PeerService.validateSiteCidrs()` message names the conflicting CIDR.
- **3a.** `publicKey`/`privateKey` mismatch (BR-008) → 400. Keypair derivation uses `wg.derivePublicKey()`; if the `wg` binary is unavailable the mismatch check is skipped with a warning.
- **4a.** `wg.setPeer()` fails → `PeerService` catches `RuntimeException`, rolls back the DB transaction (Panache @Transactional), returns 500. No DB row created.
- **5a.** nftables validation fails after successful `wg.setPeer()` → `rulesets.recomputeAndApply()` does NOT throw; peer is in DB + wg but not in nftables rules; firewall state is set to `FAILED`; old ruleset stays active; API returns 200/201 with the full peer response. Failure logged to audit as `firewall.apply_failed`.
- **5b.** nftables apply succeeds but `wg.setPeer()` failed earlier in another call-path: not possible — `wg.setPeer()` failure rolls back the whole transaction (step 4a).
- **Retention edge cases:**
  - Retention `encrypted` but no key loaded (BR-022) → 500 before peer is persisted.
  - Retention `never` but caller supplied `privateKey` → key is accepted, used to set up the WireGuard peer, and returned in response, but NOT stored (BR-021).

### UC-02: Admin changes ACL grants

**Primary Actor:** Admin  
**Trigger:** `PUT /api/v1/acl/matrix`  
**PRD reference:** PRD §8 — ACL-Verwaltung

**Extensions:**

- **2a.** No enabled peers with the affected role → nftables recompute produces an empty section for that role; no error.
- **3a.** nftables validation fails after DB grant update → DB changes are committed (the @Transactional scope in `AclMatrixResource` covers only DB writes, not the `recomputeFromHook()` call); old firewall rules remain active; `FirewallState.lastStatus = FAILED`; API returns 200 with the updated grant state. Failure logged to audit as `firewall.apply_failed`.
- **3b.** Concurrent grant update by a second admin → last writer wins; nftables reflects the final DB state after the second write's recompute.

### UC-03: End user adds device (Self-Service Portal)

**Primary Actor:** End user (OIDC-authenticated)  
**Trigger:** `POST /api/v1/me/peers` (via `MyPeerResource`)  
**PRD reference:** PRD §8 — Gerät hinzufügen (Portal)

**Extensions:**

- **0a.** OIDC provider unavailable at login time → `OidcLoginService` returns error; portal login page displays a generic error; local admin account is unaffected.
- **0b.** OIDC token is expired or signature invalid → `SessionFilter` returns 401; portal redirects to login.
- **1a.** Requested `assignedIp` already taken (BR-004) → 409. The portal UI must instruct the user to contact their admin; there is no auto-assignment of free IPs.
- **1b.** Requested `assignedIp` outside subnet (BR-003) → 400. Same resolution as 1a.
- **4a.** `wg.setPeer()` fails → 500; no DB row created. User sees a generic error and may retry.
- **5a.** nftables recompute fails → same as UC-01 extension 5a. Peer is created, but traffic is governed by the previous firewall state until the next successful recompute.

### UC-04: Operator configures islandr in Docker without the proxy (v2)

**Primary Actor:** Operator (admin)  
**Trigger:** container started via `docker run` with no `islandr-proxy` socket mounted  
**ADR reference:** ADR-0012 — configuration plane vs enforcement plane  
**Scope:** v2 (planned) — there is no PRD happy path yet, so the main scenario is given here.

**Main Success Scenario:**

1. The container boots; the socket-client adapter probes `/run/islandr/proxy.sock`, finds it absent, and sets enforcement state to `unavailable` (BR-027).
2. The operator opens the GUI and sees an "enforcement unavailable" banner linking to the install instructions (BR-031).
3. The operator configures peers, users, groups, and ACLs. Each change is persisted and marked pending; the API returns success with a "saved, not yet enforced" indication (BR-028). No enforcing call is faked (BR-029).
4. The operator runs `install.sh` on the host to bring up the proxy, then mounts its socket into the same container.
5. The adapter reconnects, reconciles the pending configuration via a full recompute, and applies it; enforcement state becomes `active` and the banner clears (BR-030).

**Extensions:**

- **1a.** Proxy socket present but unreachable (proxy crashed) → same degraded state as step 1; the adapter retries on each enforcing operation and on a periodic probe.
- **3a.** Operator prefers native operation → exports the full config as JSON (`GET /api/v1/admin/config/export`) and imports it into a native islandr install that already holds privilege; the container is then discarded.
- **5a.** Reconcile fails (the host `nft` rejects the computed ruleset) → enforcement state is `failed`, the previous host ruleset stays active, and the failure surfaces in the dashboard and audit log (consistent with §6.2 / BR-024).

**Postconditions:**

- **Success:** the configuration built while degraded is enforced on the host; enforcement state `active`.
- **Degraded (no proxy):** all configuration is persisted and exportable; nothing is enforced, and the GUI says so — no silent gap (R-122).

---

## 4. Acceptance Criteria (Gherkin — error paths not covered by existing tests)

These scenarios are not currently covered by integration tests and represent known gaps.

```gherkin
Feature: Peer IP conflict

  Scenario: Admin creates peer with already-assigned IP (BR-004)
    Given a peer exists with assignedIp "10.8.0.5"
    When an admin sends POST /api/v1/peers with assignedIp "10.8.0.5"
    Then the server responds with 409
    And the response body contains "already assigned"

  Scenario: Admin updates a peer to the same IP as an existing peer (BR-004)
    Given peer A has assignedIp "10.8.0.5" and peer B has assignedIp "10.8.0.6"
    When an admin sends PUT /api/v1/peers/{B.id} with assignedIp "10.8.0.5"
    Then the server responds with 409


Feature: nftables failure is non-fatal on peer create

  Scenario: nftables validation fails after successful wg registration (BR-024)
    Given MockNftablesAdapter is configured to fail validation
    When an admin creates a new peer
    Then the API response is 200 with the peer data
    And the peer exists in the database
    And FirewallState.lastStatus is "FAILED"
    And an audit entry "firewall.apply_failed" is written


Feature: Private key retention

  Scenario: Retention mode "never" — private key not stored (BR-021)
    Given settings.privateKeyRetention is "never"
    When an admin creates a new peer (server-generated keypair)
    Then the creation response includes the private key
    And a subsequent GET /api/v1/peers/{id} does not include the private key
    And the database column peer.private_key_pem is NULL

  Scenario: Retention mode "encrypted" without key configured (BR-022)
    Given settings.privateKeyRetention is "encrypted"
    And EncryptionService is not configured (no ISLANDR_ENCRYPTION_KEY)
    When an admin creates a new peer
    Then the server responds with 500
    And no peer row is created in the database


Feature: Site CIDR overlap

  Scenario: New site peer CIDRs overlap an existing site peer (BR-017)
    Given site peer "Office A" declares siteAllowedCidrs "192.168.10.0/24"
    When an admin creates a new site peer with siteAllowedCidrs "192.168.10.0/28"
    Then the server responds with 400
    And the error message references "Office A"


Feature: Enforcement degraded mode (v2 — ADR-0012)

  Scenario: Container boots without a proxy (BR-027, BR-031)
    Given the container is started with no proxy socket mounted
    When an admin opens the GUI
    Then the GUI is reachable
    And an "enforcement unavailable" banner with install instructions is shown

  Scenario: ACL change is saved but not enforced while degraded (BR-028, BR-029)
    Given the proxy socket is unavailable
    When an admin applies an ACL change
    Then the change is persisted and marked pending
    And the API response indicates "saved, not yet enforced"
    And no enforcing wg/nft call reports a fake success

  Scenario: Pending config is reconciled on proxy connect (BR-030)
    Given pending configuration exists from degraded mode
    When the proxy socket becomes available
    Then islandr recomputes and applies the full ruleset
    And enforcement state becomes "active"
    And the "enforcement unavailable" banner clears
```
