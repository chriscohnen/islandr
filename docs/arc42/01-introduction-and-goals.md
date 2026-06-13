# 1. Introduction and Goals

## 1.1 Purpose

Islandr replaces manual WireGuard CLI work on a hub-spoke VPN with a web UI that covers three concerns together: peer lifecycle management, group-based access control (RBAC), and nftables firewall enforcement derived from that model. No existing open-source tool covers all three while remaining fully self-hostable and vendor-neutral.

The four concrete goals (from the [PRD](../prd.md)):

- **G-1** — Peer creation via UI: keypair generation, IP assignment, one-time QR + `.conf` delivery.
- **G-2** — Access modelled as roles granting access to named resources inside sites. Firewall recomputed from the model.
- **G-3** — nftables rules generated and atomically reloaded from the ACL model. No manual rule editing, no drift.
- **G-4** — Self-service portal for end users: German-language, non-technical. "Worauf du zugreifen darfst" — never raw CIDR.

## 1.2 Quality Goals

The following five goals drive architecture decisions. They are ordered by priority.

| ID | Quality Goal | Scenario (brief) |
|---|---|---|
| Q-1 | **Security** | Hub VM compromise does not expose internal network credentials or reconfigure the UCG firewall. |
| Q-2 | **Correctness** | Every ACL change produces a valid, atomically applied nftables ruleset within 2 seconds. Existing connections survive reload. |
| Q-3 | **Operability** | A fresh Ubuntu 22.04+ VM is running Islandr within 5 minutes of downloading the binary. No JVM, no database server, no build tool required. |
| Q-4 | **Usability** | Admin onboards a new peer in under 2 minutes; peer disable drops packets within 1 second. A non-technical end user adds a device without asking IT. |
| Q-5 | **Auditability** | Every mutating action is captured in an immutable audit log with actor, action, target, and timestamp. The log is accessible via API and UI. |

Detailed quality scenarios with response measures are in [Chapter 10](10-quality-requirements.md).

## 1.3 Stakeholders

| Stakeholder | Role | Expectations |
|---|---|---|
| Felix (Admin) | Sysadmin, 30–80 person company or technical home setup. Comfortable with Linux CLI, often in dark mode. | Dense data, exact values (IPs, public keys, handshake times as absolute timestamps on hover), fast ACL changes. Revoking access takes one click and the firewall is correct within 1 second. |
| Lena (End User) | Knowledge worker, German-speaking, non-technical. Knows what a Wi-Fi password is. Will give up if she sees the word "CIDR" or "Public Key". | German UI, plain language ("Gerät", not "Peer"), "Verbindung erkannt" feedback. Can onboard a phone without contacting Felix. |
| Tom (Power User) | Technical employee, not admin. Comfortable with the CLI, does not want to email his admin. | `.conf` download path for Linux workstation, no QR needed. Same Self-Service Portal as Lena, different path. |
| Operator / Self-hoster | Runs the hub VM | Single binary install, SQLite file backup, optional Postgres. No SaaS dependency. |
| Developer / Contributor | Extends Islandr | `git clone && ./gradlew quarkusDev` works immediately. Clear package boundaries. |
