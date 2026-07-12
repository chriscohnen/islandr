# Islandr — Architecture

Self-hosted WireGuard access management. Islandr manages the full peer
lifecycle, group-based RBAC access control, and nftables enforcement on a
single hub — one native binary, no SaaS, no control plane you don't own.

This site is the interactive C4 model of the system, generated from
`architecture/workspace.dsl` in the [islandr repository](https://github.com/chriscohnen/islandr).
It is the source of truth for how the pieces fit together; the PNG exports in
the repo's `docs/` are rendered from the same model.

## ▶ Start here: the Islandr container view

The diagrams live under the **Islandr** software system — that is the entry
point. Open **[Islandr → Container view](islandr/container/)** to see the
runnable parts, then drill down: click a container to zoom into its components,
or switch to the deployment views.

## One system, plus its context

This model documents **exactly one** software system: **Islandr**. Everything
else in the *Software Systems* list is an **external dependency** Islandr talks
to — those pages carry a short description and a context diagram, but no
internal views, because they are not ours to design.

| System | Role | Has internal views? |
|--------|------|:---:|
| **Islandr** | The platform itself — hub backend, both SPAs, database | **Yes — start here** |
| OIDC Provider | Authenticates users (Microsoft 365 / Google Workspace) | No (external) |
| WireGuard | Linux kernel VPN module, managed via `wg` | No (external) |
| nftables | Linux kernel packet filter, enforces the ACL ruleset | No (external) |
| Cloudflare / Reverse Proxy | Edge and TLS termination | No (external) |
| Resource Host | A machine behind the VPN (e.g. an RDP server) | No (external) |

So if a system in the sidebar shows only text, it is external context. The one
you can walk through is Islandr.

## How to read this model (C4)

The Islandr system follows the [C4 notation](https://c4model.com) — four zoom
levels, each a tab on the **Islandr** page:

| Level | View | What it answers |
|-------|------|-----------------|
| 1 | **Context** | Who uses Islandr and which external systems it touches |
| 2 | **Container** | The runnable parts: the two SPAs, the Quarkus backend, the database |
| 3 | **Component** | Inside the backend: `auth`, `acl`, `peer`, `firewall`, `wg`, `audit`, … |
| — | **Deployment** | How containers map onto real infrastructure — **native (systemd)** and **Docker (evaluation)** |

## Related documentation

- **[PRD](product-requirements/)** — the product requirements: problem, goals, personas
  (Felix, Lena, Tom), scope, domain model, and key flows.
- **[Roadmap](roadmap/)** — what's shipped, in progress, and planned.
- **[Decisions](islandr/decisions/)** — the architecture decision records (ADRs)
  behind the design, browsable per decision.
- **[README](https://github.com/chriscohnen/islandr#readme)** — install, run,
  and operate Islandr.
