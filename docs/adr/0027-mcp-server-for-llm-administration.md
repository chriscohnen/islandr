# ADR-0027 — MCP server for LLM-assisted administration: separate adapter process over the external API facade

**Status:** Proposed
**Date:** 2026-08-23
**Deciders:** Christian Cohnen
**Target release:** none yet — not filed as a GitHub issue, tracked only as a `TODO.md` idea pending [ADR-0026](0026-external-api-facade.md)'s facade growing past peers/users

## Context

`TODO.md` (2026-08-23) proposes an [MCP](https://modelcontextprotocol.io) server so an LLM assistant can administer/query Islandr — "list peers", "create a peer", "check an ACL grant" — as tools an assistant calls directly. Two shapes were sketched:

1. **A native MCP endpoint inside Quarkus** — `GET /api/mcp/sse` (event stream) + `POST /api/mcp/message` (JSON-RPC `tools/list`/`tools/call`), with a Settings toggle and an `mcp:access`-scoped API key, translating the running app's own JAX-RS resources to MCP tool calls at runtime.
2. **A separate, small adapter process** outside islandr's own codebase (a different repo/binary, e.g. Node or Go) that speaks MCP to the assistant on one side and calls the already-existing `/api/external/v1/...` facade ([ADR-0026](0026-external-api-facade.md)) on the other, deriving its tool definitions from the hand-written `docs/api/openapi.yml` rather than any runtime introspection.

This is the same fork every prior "do we add a protocol surface" decision in this project has faced — ADR-0019 (ACME client), ADR-0023 (DNS resolver), ADR-0012 (Docker socket proxy), and ADR-0026 itself (OpenAPI spec: hand-written, not `quarkus-smallrye-openapi`) all chose to keep an additional protocol or generation concern **out of** the core native-image binary rather than pull in a library or annotation processor to serve it. MCP is a heavier version of the same question: an MCP SDK (session/transport handling, JSON-RPC framing, capability negotiation) is a dependency with its own update cadence, attack surface, and native-image reflection-config burden, added to every user's binary, to serve an assistant-integration use case only some operators want.

This time the question isn't only build size — it's what the operator has to *run*. Islandr's primary deployment promise (README, `docs/install.md`) is **one systemd unit running one native binary**; Docker is the secondary path, and even there ADR-0012 fought hard to keep the footprint to exactly one additional process (`islandr-proxy`, a small Go daemon with a narrow allowlisted protocol) rather than a stack of containers. That sidecar was accepted because it closes a real capability gap no in-process code could (the container has zero Linux capabilities and needs *something* on the host to call `wg`/`nft`) — not because sidecars are free. A second sidecar for MCP has no equivalent forcing function: nothing about talking MCP-to-REST requires living outside the container/host boundary the way `wg`/`nft` calls do. Every option below is judged against that bar, not just against native-image size.

The facade this would sit on is also not yet ready for meaningful LLM administration: [ADR-0026](0026-external-api-facade.md) originally scoped v1 to peers (list/create) and users (list) only — no ACL grants, no sites/resources. An MCP layer at that point could offer little more than "list peers" and "create a peer", which doesn't yet justify the integration on its own.

**Update (2026-08-23):** sites (list), resources (list), and roles (list) have since been added to the facade (read-only), narrowing this gap. **ACL grants themselves (which role/user can reach which resource) are still internal-only** — the one piece still missing for an assistant to actually answer "who can reach X" or "grant Y access to Z", which was the concrete example in the originating `TODO.md` idea ("ACL-Grant prüfen"). That remains the blocking precondition below.

## Decision

**Defer building either option until the external facade covers enough resources to make LLM administration meaningful** (ACL grants, at minimum — sites/resources/roles are covered as of 2026-08-23). When that precondition is met, build the MCP surface as **a separate adapter process, not a native Quarkus endpoint** — the same "adapter process talking to a stable API, not a dependency inside the core binary" shape as [ADR-0012](0012-docker-socket-proxy.md)'s socket proxy.

Concretely, once undertaken:

- The adapter is its own repository/binary (language TBD — Node or Go are the obvious fits for an MCP SDK's maturity), not a Gradle module or dependency of `islandr` itself. Nothing in `build.gradle.kts` changes.
- It authenticates to Islandr the same way any external automation does: an API key against `/api/external/v1/...` ([ADR-0026](0026-external-api-facade.md)). No new auth mechanism, no `mcp:access` scope carve-out — v1 API keys are already all-or-nothing per ADR-0026's own decision (R-184), and a separate scope would be a half-step that still doesn't bound what a leaked key can do.
- Its MCP tool definitions are derived from `docs/api/openapi.yml` (offline, e.g. an `openapi-generator`-style step run by a maintainer or a build step in the adapter's own repo) — the same "the spec is the interface, not something introspected at runtime" posture ADR-0026 already established for the facade itself. Islandr never needs to know an MCP client exists.
- No toggle in Islandr's Settings page for "enable MCP" — `Settings.externalApiEnabled` (ADR-0026) already gates the whole facade the adapter depends on; a second toggle for the same surface would be redundant state to keep in sync.
- **Strictly opt-in, never part of the default install.** An operator who never wants LLM administration never downloads, runs, or updates the adapter — it isn't in the systemd unit, the release tarball, or the Docker Compose file `docs/install.md` ships. This is what keeps the "single binary" promise intact for everyone who doesn't ask for MCP: the cost of this decision is paid only by the operator who opts in, unlike ADR-0012's socket proxy, which every Docker-mode operator runs whether they use it or not (it isn't optional — the container cannot reach `wg`/`nft` without it).

## Installation and development effort

Explicitly weighed, not just implied by the Pugh Matrix below:

- **Installation effort for the operator who wants it.** A separate adapter process is a second thing to download, run, keep alive (its own systemd unit or container), and update on its own schedule, version-matched against whatever facade shape it was built for — real ongoing operational burden, same category as ADR-0012's `islandr-proxy` but *not* offset by that ADR's justification (a capability the host process structurally cannot have). A native endpoint has zero install cost for this operator: it's already running, a Settings toggle turns it on.
- **Development/maintenance effort for the project.** A separate repo means a second thing to build, test, version, and keep compatible with the facade as it evolves — real recurring cost for a solo-maintained project, and a second release cadence to reason about (does the adapter need a new version every time the facade gains an endpoint?). Against that: MCP's actual protocol surface (session/transport, JSON-RPC framing, capability negotiation) is far cheaper to get right with a mature SDK in Node or Go than hand-rolled in Java against native-image reflection constraints — building it *in* Quarkus is not simply "1–2 controllers" as the original TODO sketch assumed (see Alternatives below).
- **Net read:** the adapter is more expensive to install and maintain than a toggle would be — that cost is real and is scored explicitly below, not waved away by "it's just a small binary." It's accepted here because (a) it's opt-in, so the base install stays a single binary for every operator who doesn't ask for this, and (b) it avoids a worse, permanent cost — MCP SDK weight and attack surface baked into every native-image build forever, paid by 100% of operators to serve a feature maybe 5% want. A sidecar that only the opted-in 5% install is the smaller total burden even though it is a *bigger per-operator* burden for that 5%.

## Alternatives considered (Pugh Matrix)

Baseline: **separate adapter process over the external facade, deferred until the facade covers more resources** (the decision). +1 better, 0 equal, −1 worse.

| Criterion (weight) | Separate adapter process (baseline) | Native Quarkus MCP endpoint (`/api/mcp/sse`, in-process) | Build now, against today's peers/users-only facade |
| --- | --- | --- | --- |
| Native-image build size / dependency surface (5) | 0 | −1 | 0 |
| Facade already provides everything needed (auth, DTOs, stable contract) (4) | 0 | −1 | 0 |
| Usefulness of the resulting tool surface to an assistant (4) | 0 | 0 | −1 |
| Implementation effort right now (2) | 0 | −1 | +1 |
| Consistent with prior "adapter/hand-rolled over library" ADRs (0012/0019/0023/0026) (3) | 0 | −1 | 0 |
| Installation effort for the operator who opts in (extra process to run/update) (4) | 0 | +1 | 0 |
| Development/maintenance effort for the project (second repo, release cadence, version compat) (3) | 0 | +1 | 0 |
| Installation effort for operators who *don't* want MCP (the default-install promise) (5) | 0 | −1 | 0 |
| **Weighted total** | **0** | **−13** | **−4** |

Notes:

- **Native Quarkus MCP endpoint** — the TODO's own "Weg 1" sketch reads as the least-code option ("1–2 schlanke Controller"), but that undersells an MCP SDK's actual footprint: session/transport handling, JSON-RPC framing, and capability negotiation aren't a couple of `@Path` methods, and pulling in the dependency (or hand-rolling the protocol) inside the shipped native image repeats exactly the trade-off ADR-0026 just rejected for OpenAPI generation — paying a build-time/binary-size cost in every user's binary for a feature only some operators use. It also duplicates auth (a new `mcp:access` scope) instead of reusing the facade's existing API keys. It does win two real criteria — nothing extra to install/update for the operator who wants it, and one release cadence instead of two for the maintainer — which is exactly why this decision is close on paper (−13 vs. the −4 "build now" option is not a landslide) rather than obvious; it loses on the criterion weighted highest here: what every *other* operator has to install, which a permanent binary-size/dependency cost affects and an opt-in sidecar doesn't.
- **Build now, against today's facade** — cheapest in the short term, but the facade only exposes peers/users; an assistant that can list peers and users but can't touch ACL grants, sites, or resources isn't yet a useful "administer Islandr" tool. Building the adapter now means rebuilding its tool surface again as soon as the facade grows, for no benefit today. Its installation/effort scores match the baseline since it's the same shape (adapter process), just built prematurely.

## Consequences

- No code changes to `islandr` itself from this ADR — it records a decision on *shape*, not an implementation. The facade (ADR-0026) is the only dependency, and it already exists.
- Precondition before implementation starts: `/api/external/v1/...` needs ACL-grant endpoints (which role/user can reach which resource) — sites, resources, and roles are covered as of 2026-08-23, but the grants connecting them are still internal-only (natural follow-on work to ADR-0026, tracked in `TODO.md`, not yet a GitHub issue).
- When implementation starts, it gets its own repository and its own README/tests — this ADR does not cover that adapter's internal design, only that it stays external to `islandr`.
- **Ongoing cost accepted knowingly:** a second release cadence to track against facade changes, and — for every operator who opts in — a second process to install, run, and keep updated, on top of the systemd-unit/native-binary or Docker Compose install `docs/install.md` already documents. `docs/install.md` gets a new, clearly-optional section for it when implementation starts; it is never folded into the default install steps.
- **R-186** — An MCP adapter is, from Islandr's perspective, just another external-facade API-key consumer — it inherits every risk the facade already carries (R-184: leaked key is full-admin-equivalent; R-185: spec drift) plus a new one specific to giving an LLM agent write access: an assistant acting on ambiguous or adversarial (prompt-injected) instructions could call `tools/call` → facade write endpoints (e.g. create a peer, change an ACL grant) without the human noticing until the audit log is reviewed. Mitigation: the facade's existing one-time-reveal/revocable/audit-logged API key model (ADR-0026) is the only control at v1 — no additional agent-specific guardrail (e.g. a human-confirmation step, a read-only key mode) is designed here; if the adapter is built, scoped/read-only key modes should be revisited as a precondition, not an afterthought.
- **T-020** — An MCP adapter process is a new consumer of external-API keys, and by extension a new place a key can be minted, stored, or leaked (e.g. in the adapter's own config, or in an LLM host's tool-call logs) outside Islandr's control entirely. STRIDE: Spoofing (the adapter's key, once leaked, is indistinguishable from any other API key), Elevation of Privilege (an LLM agent with a live key can act with full admin privilege, same blast radius as R-184). Mitigation ties to R-186 and the existing T-019 controls (ADR-0026): one-time reveal, hashed at rest, revocable, audit-logged — nothing MCP-specific exists yet because nothing MCP-specific has been built.

## References

- `TODO.md` (2026-08-23, "MCP-Server für LLM-Zugriff") — the originating idea, including the "Weg 1" native-endpoint sketch this ADR evaluates and declines
- [ADR-0026](0026-external-api-facade.md) — the external API facade this adapter would sit on top of; its ACL-grant coverage (not yet built as of 2026-08-23) is this ADR's blocking precondition
- [ADR-0012](0012-docker-socket-proxy.md) / [ADR-0019](0019-acme-hand-rolled-client.md) / [ADR-0023](0023-resource-dns-resolver-hand-rolled.md) — precedent for "separate process / hand-rolled, not a dependency in the core binary" that this ADR follows
- [Issue #15](https://github.com/chriscohnen/islandr/issues/15) / [Issue #71](https://github.com/chriscohnen/islandr/issues/71) — the facade's own implementation targets, whose growth is this ADR's precondition
