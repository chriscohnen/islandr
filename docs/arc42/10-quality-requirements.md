# 10. Quality Requirements

## 10.1 Quality Tree

```
Islandr Quality
├── Security (Q-1) ← top goal, drives ADR-0005, ADR-0007, ADR-0011
│   ├── Confidentiality   — private keys never at rest (default)        ← concretises Q-1
│   ├── Integrity         — nftables rules always match DB state         ← concretises Q-1
│   └── Non-repudiation   — every mutation is audit-logged              ← concretises Q-1
├── Correctness (Q-2) ← top goal, drives ADR-0003, ADR-0006
│   ├── Ruleset validity  — nft -c -f passes before every apply         ← concretises Q-2
│   ├── Atomicity         — nft -f is all-or-nothing; no partial state  ← concretises Q-2
│   └── Zero-drift        — ruleset recomputed from DB on every change  ← derived from Q-2
├── Operability (Q-3) ← top goal, drives ADR-0001, ADR-0004
│   ├── Single binary     — no JVM, DB server, or build tool required   ← concretises Q-3
│   ├── Fast startup      — binary starts in < 200ms                    ← derived from Q-3
│   └── Zero-downtime config — settings changes via DB, no restart     ← concretises Q-3
├── Usability (Q-4) ← top goal, drives ACL UI and portal design
│   ├── Admin efficiency  — peer creation < 2 min                      ← concretises Q-4
│   ├── End-user clarity  — plain language, no CIDR, German informal Du ← concretises Q-4
│   └── Accessibility     — WCAG AA contrast on both themes             ← derived from Q-4
└── Auditability (Q-5) ← top goal, drives audit package
    ├── Completeness      — 100% of mutating actions logged              ← concretises Q-5
    └── Immutability      — no delete/update path for audit entries      ← concretises Q-5
```

## 10.2 Quality Scenarios

Each scenario uses the six-part form: Source / Stimulus / Artifact / Environment / Response / Response Measure.

### QS-1 — Hub VM Compromise Blast Radius (Security)

| Part | Detail |
|---|---|
| **Source** | External attacker with root access to the hub VM |
| **Stimulus** | Searches for credentials and connection paths into the internal trusted network |
| **Artifact** | Hub VM filesystem, running processes, environment variables |
| **Environment** | Production: hub VM is internet-exposed, UCG sites have established tunnels |
| **Response** | Attacker finds no UCG credentials, no outbound connections into the trusted network, no capability to reconfigure UCG from the hub |
| **Response Measure** | Zero: `find / -name '*.conf' -o -name '*.env' -o -name '*.key'` on the hub VM returns no file containing a UCG credential pattern. Verifiable by the credential-scan step in the install guide post-deploy acceptance checklist. T-001 mitigated per [ADR-0005](../adr/0005-hub-only-firewall.md). |

Maps to: Q-1 Security · T-001

### QS-2 — ACL Change Applied Correctly and Atomically (Correctness)

| Part | Detail |
|---|---|
| **Source** | Admin Felix |
| **Stimulus** | Changes a role grant (removes RDP access to Terminal-01 for role "Vertrieb") and clicks "Änderungen anwenden" |
| **Artifact** | nftables ruleset on hub VM |
| **Environment** | Production: 20 active peers, existing connections open |
| **Response** | System recomputes ruleset, validates with `nft -c -f`, atomically applies with `nft -f`; new connections from Vertrieb peers to Terminal-01 port 3389 are dropped; existing connections survive the reload |
| **Response Measure** | Ruleset applied within 2 seconds of API call; `nft list ruleset` no longer contains the removed rule; existing SSH sessions are not interrupted |

Maps to: Q-2 Correctness · F-08

### QS-2b — Peer Disable Enforced Immediately (Correctness)

| Part | Detail |
|---|---|
| **Source** | Admin Felix |
| **Stimulus** | Clicks "Peer deaktivieren" on an active peer (e.g. offboarding) |
| **Artifact** | WireGuard interface and nftables ruleset on hub VM |
| **Environment** | Production: peer has an active VPN session |
| **Response** | Backend removes the peer from the WireGuard interface (`wg set wg0 peer <pubKey> remove`) and triggers a full nftables ruleset recompute + reload. Subsequent packets from the peer's former IP are dropped. |
| **Response Measure** | Packets from the disabled peer are dropped within 1 second of the disable API call. Verifiable with `tcpdump` on the hub during the disable action. (PRD S-2) |

Maps to: Q-2 Correctness · F-04

### QS-3 — First-Time Installation (Operability)

| Part | Detail |
|---|---|
| **Source** | Operator |
| **Stimulus** | Downloads the native binary on a fresh Ubuntu 22.04 VM with WireGuard configured |
| **Artifact** | Islandr binary |
| **Environment** | Production server, no prior Islandr state, `ISLANDR_ADMIN_PASSWORD` set in environment |
| **Response** | Binary starts, Flyway creates schema, AdminBootstrap creates admin user, Admin Console is reachable |
| **Response Measure** | Time from `./islandr` to first successful `GET /api/v1/status` response ≤ 30 seconds; no additional software installed |

Maps to: Q-3 Operability · N-01, N-02, N-03

### QS-4 — Admin Onboards a New Peer (Usability)

| Part | Detail |
|---|---|
| **Source** | Admin Felix |
| **Stimulus** | A new employee joins; Felix must create a peer and hand them access |
| **Artifact** | Admin Console peer creation flow |
| **Environment** | Normal operation; user already exists in the system |
| **Response** | Felix navigates to the user, clicks "Peer erstellen", sees QR + `.conf` |
| **Response Measure** | Entire flow (from landing on user detail page to QR visible on screen) completes in under 2 minutes |

Maps to: Q-4 Usability · S-1

### QS-5 — End User Adds Device Without Admin Help (Usability)

| Part | Detail |
|---|---|
| **Source** | End user Lena |
| **Stimulus** | IT told her to "set up the VPN on your phone" |
| **Artifact** | Self-Service Portal 3-step enrollment flow |
| **Environment** | Lena is logged in via Microsoft 365; Admin Console not involved |
| **Response** | Lena picks iOS, sees QR, scans it with the WireGuard app, toggles VPN on, portal shows "Verbindung erkannt" |
| **Response Measure** | Lena completes the 3-step enrollment in under 5 minutes on a first attempt; zero occurrences of the strings "CIDR", "Public Key", or raw IPv4/IPv6 addresses in the portal's visible text (verifiable by automated HTML scan of the rendered portal); no IT support ticket created. S-4 achieved. |

Maps to: Q-4 Usability · F-17

### QS-6 — Audit Log Completeness (Auditability)

| Part | Detail |
|---|---|
| **Source** | Security auditor |
| **Stimulus** | Requests the audit trail for all actions on a specific peer over 30 days |
| **Artifact** | Audit log table, `GET /api/v1/audit` endpoint |
| **Environment** | Production |
| **Response** | API returns chronological, immutable list of all mutations involving the peer (create, disable, delete, grant changes affecting the peer's user) |
| **Response Measure** | 100% of mutating API calls produce an audit entry; no entry can be deleted via any API endpoint; log covers the full 30-day window |

Maps to: Q-5 Auditability · F-10
