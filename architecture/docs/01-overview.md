# Islandr — Architecture

Self-hosted WireGuard access management. Islandr manages the full peer
lifecycle, group-based RBAC access control, and nftables enforcement on a
single hub — one native binary, no SaaS, no control plane you don't own.

This site is the interactive C4 model of the system, generated from
`architecture/workspace.dsl` in the [islandr repository](https://github.com/chriscohnen/islandr).
It is the source of truth for how the pieces fit together; the PNG exports in
the repo's `docs/` are rendered from the same model.

## What Islandr is

A machine (the **hub**) terminates WireGuard, and Islandr runs on it. Admins
manage peers, users, roles, sites and resources; end users enroll their own
devices through a guided self-service portal. Access is enforced at the hub by
nftables rules that Islandr computes from the ACL model — the firewall is the
single choke point, so a peer can only reach what its roles grant.

- **Deployment:** one native binary under `systemd` (production), or a Docker
  container (evaluation today; production Docker via a Unix socket proxy is on
  the roadmap — see below).
- **Auth:** local login or OIDC (Microsoft 365 / Google Workspace).
- **Storage:** SQLite by default, PostgreSQL for larger installs.

## How to read this model (C4)

The model follows the [C4 notation](https://c4model.com) — four zoom levels,
each a tab on the **Islandr** software-system page:

| Level | View | What it answers |
|-------|------|-----------------|
| 1 | **Context** | Who uses Islandr and which external systems it touches |
| 2 | **Container** | The runnable parts: the two SPAs, the Quarkus backend, the database |
| 3 | **Component** | Inside the backend: `auth`, `acl`, `peer`, `firewall`, `wg`, `audit`, … |
| — | **Deployment** | How containers map onto real infrastructure — **native (systemd)** and **Docker (evaluation)** |

Start with **Container views** for the overview, drop into **Component views**
to see the backend's internal packages, and use **Deployment views** to see how
it all lands on a host.

## Related documentation

- **[Roadmap](roadmap/)** — what's shipped, in progress, and planned.
- **Decisions** — the architecture decisions (ADRs) live in the repo under
  [`docs/adr/`](https://github.com/chriscohnen/islandr/tree/main/docs/adr).
- **[README](https://github.com/chriscohnen/islandr#readme)** — install, run,
  and operate Islandr.
