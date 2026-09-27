# ADR-0032 — Islandr sits on the Quarkus LTS line, identified by its four-segment backports

**Status:** Accepted (implemented for 1.0.0 — moved 3.29.4 → 3.33.3.2)
**Date:** 2026-09-19
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0001](0001-quarkus-backend.md) (why Quarkus at all), [ADR-0028](0028-webauthn-library-and-integration.md) (R-187 depends on when the platform adopts Vert.x 5)

## Context

Islandr pins one Quarkus version and treats it as the base for everything
transitive. The intent has always been to sit on a **LTS** line: a stable base
that keeps receiving security backports, so CVE fixes arrive without chasing the
newest release and without churn.

That intent was not what the build actually did. Until 2026-09-19 the pin was
**3.29.4**, and `build.gradle.kts` described it in a comment as LTS. It was not.
Quarkus publishes LTS security backports as releases with a **fourth version
segment** (`3.33.2.1`, `3.20.6.2`), and checking Maven Central for that pattern
gives an unambiguous answer:

| Line | Four-segment releases |
|---|---|
| 3.8 | `3.8.6.1` |
| 3.15 | `3.15.3.1`, `3.15.6.1`, `3.15.6.2` |
| 3.20 | `3.20.2.1`, `3.20.2.2`, `3.20.6.1`, `3.20.6.2` |
| 3.27 | `3.27.3.1`, `3.27.4.1`, `3.27.5.1`, `3.27.5.2` |
| 3.33 | `3.33.1.1`, `3.33.2.1`, `3.33.3.1`, `3.33.3.2` |
| **3.29** | **none** |

3.29 was an ordinary release. Its line ended at `3.29.4` and nothing further
shipped. The pin therefore bought a frozen dependency tree rather than LTS
support, and it did so invisibly — the version number looks like any other, and
nothing in the build fails when a line stops being maintained.

The cost was concrete. Three of the four open Dependabot families were
unfixable from 3.29.4:

- **Netty was capped at `4.1.135.Final`.** `4.1.136` changed the constructor of
  `io.netty.handler.ssl.ReferenceCountedOpenSslClientContext`, which 3.29.4's
  Vert.x GraalVM substitution called with the old signature. The native build
  failed while the JVM test suite passed — so the cap held the one *critical*
  advisory open, and no same-line force could lift it.
- **`quarkus-vertx-http`** was fixed in **3.33.2.1** — present in LTS,
  unreachable from a line that had stopped.
- **`opentelemetry-api`** sat sixteen minor versions behind the fixed release,
  too far to treat as a patch bump.

## Decision

**Pin to the current Quarkus LTS line, and identify LTS by the four-segment
backport pattern rather than by assumption.** As of this decision that is
**3.33.3.2**.

This is not a move to the newest Quarkus — the newest release at the time was
`3.40.0.CR1`. It is a move *onto* LTS, which is what the pin was always meant to
express.

### Alternatives considered

| | Stay on 3.29.4 | **Move to 3.33 LTS** | Move to newest (3.40) |
|---|---|---|---|
| Security backports keep arriving | −1 (line is dead) | **+1** | 0 (until the line ends) |
| Critical Netty advisory closable | −1 | **+1** | +1 |
| Migration effort | +1 (none) | **0** (four minors) | −1 (eleven minors) |
| **Total** | **−1** | **+2** | **0** |

## Consequences

The forced-version block shrinks from five overrides to one. The 3.33 BOM already
ships jackson `2.21.5`, postgresql `42.7.13` and vertx-core `4.5.31` — every
force carried for those was equal to or older than the BOM, so keeping them would
have pinned the build *backwards*. What remains is Netty at `4.1.137.Final`, one
step past the BOM's `4.1.136`, because CVE-2026-59898 is fixed only there.

**A blanket `io.netty` force is a trap and the block now says so.** The
`netty-tcnative*` artifacts live on their own `2.0.x` line and have no `4.1.x`
release at all; forcing the group as a whole asks for a
`netty-tcnative-classes:4.1.137.Final` that does not exist, and dependency
resolution fails before anything compiles.

**Verification of this class of change requires a native build.** The Netty cap
existed for a failure that only the native image path produces — 1069 JVM tests
pass either way. Any future change to the Netty force must be verified with
`testNative`, not with the test suite.

**R-187 did not fire.** [ADR-0028](0028-webauthn-library-and-integration.md)
records that a Vert.x 5 platform upgrade would force a
`vertx-auth-webauthn` → `vertx-auth-webauthn4j` migration Islandr performs
itself. The 3.33 BOM still resolves Vert.x **4.5.31** and still ships
`vertx-auth-webauthn`, so the WebAuthn code is untouched. The risk stays open
and unchanged; it is a question of *when* Quarkus adopts Vert.x 5.

This decision creates **R-195**: an LTS line also ends eventually, and nothing in
the build notices when it does. The mitigation is the rule written into
`build.gradle.kts` — check the four-segment pattern when triaging dependency
advisories, and treat "no backports since X" as the signal to move, rather than
waiting for an advisory that cannot be fixed.

It leaves **opentelemetry-api** deliberately unforced. The 3.33 BOM resolves
`1.57.0` against a fixed `1.62.0` — five minors instead of sixteen, tractable now
but still needing verification against the Quarkus-managed OTel SDK rather than a
one-line force (issue #20).
