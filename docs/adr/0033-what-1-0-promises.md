# ADR-0033 — What the 1 before the dot covers, and what it does not

**Status:** Accepted
**Date:** 2026-09-19
**Deciders:** Christian Cohnen
**Relates to:** [ADR-0026](0026-external-api-facade.md) (which already drew this line for the API), [ADR-0032](0032-quarkus-lts-line.md) (a dependency base that keeps receiving fixes is part of the promise), [ADR-0002](0002-vue-without-npm.md) (why the frontend is not a published interface)

## Context

Islandr is going to 1.0. A leading 1 is read as a promise, and an unstated
promise is worse than none: every later change becomes either a broken word or
something that was never meant, decided after the fact and in the project's own
favour.

The promise people actually want from infrastructure software is not "finished".
It is **that upgrading stops being an event**. Islandr has not earned that yet by
behaviour — 0.21.0 needed `systemctl edit` and 0.22.0 needed `setup-hub.sh` run
again — so the number has to say what it covers precisely enough to be held to.

Half of this was already decided. [ADR-0026](0026-external-api-facade.md) split
the API in two for exactly this reason: `/api/external/v1` is "a
stability-committed contract", while `/api/v1` is "free to change as the SPA
evolves". That line was drawn for one surface. The question now is which *other*
surfaces sit on which side of it.

## Decision

**Semantic versioning applies to the surfaces below, and only to those. A major
version is required to break any of them.**

**Covered:**

- **The external API** — `/api/external/v1`, its paths, its request and response
  shapes, and its documented semantics. Fields may be added; existing fields
  keep their meaning. This continues ADR-0026 rather than extending it.
- **Configuration** — the `ISLANDR_*` environment variables and the
  `/etc/default/islandr` file. A key keeps its name and its meaning; a removed
  key is a breaking change, and a renamed one keeps the old name working.
- **Database migrations** — always forward, always automatic. An upgrade never
  asks an operator to run SQL, and never requires a dump-and-restore.
- **The install and update scripts** — `setup-hub.sh`, `install-proxy.sh`,
  `update.sh` and `backup.sh`, in the interface they present: their flags, their
  environment variables, and the paths they write.
- **The on-disk layout** an operator interacts with: the database location, the
  systemd unit name, the sudoers scope.

**Not covered:**

- **The console API** (`/api/v1`). It exists to serve one SPA shipped in the same
  binary, and it changes with it. Anything relying on it should move to the
  external API; where the external API cannot answer, that is a gap to report,
  not a reason to treat `/api/v1` as public.
- **The frontend ES modules and CSS.** There is no build step
  ([ADR-0002](0002-vue-without-npm.md)) and therefore no published module
  surface. File names, component structure and tokens change freely.
- **Internal Java packages.** Islandr ships a binary, not a library.
- **The database schema itself.** Migrations are the promise; the shape they
  arrive at is not. Reading the SQLite file directly is diagnosis, never
  integration.
- **The WireGuard interface config.** By
  [ADR-0030](0030-wireguard-config-file-ownership.md) that file belongs to the
  admin, and Islandr does not write it — there is nothing here to promise.

**Downgrades are not covered in either direction.** Migrations run forward only,
so going back means restoring the backup `update.sh` took.

### Alternatives considered

| | Promise everything | **Promise named surfaces** | Stay on 0.x |
|---|---|---|---|
| A reader can tell what may break | 0 (too broad to believe) | **+1** | −1 (nothing is said) |
| Leaves room to keep improving the console | −1 | **+1** | +1 |
| Honest about what is actually tested | −1 | **0** | +1 |
| **Total** | **−2** | **+2** | **+1** |

"Stay on 0.x" scores well and was rejected anyway: it is the option that avoids
the question rather than answering it, and after twenty-three releases in
production use the version number has stopped describing the software.

The middle row was the uncomfortable one. Promising the install scripts meant
promising something CI did not exercise: the upgrade path was covered only from
the current release forward, and `update.sh --rollback` ran in no job at all.
That was named here as a gap to close before the tag rather than a reason to
promise less, and the job was built for 1.0.0 — CI installs the previous
release, upgrades to the tag and rolls back, asserting after each step. It
fires on tags only, so the first proof arrives with the first RC; until that
run is green the promise is instrumented, not demonstrated (R-196). A promise
nobody verifies is the failure mode this ADR exists to avoid.

## Consequences

**The external API's shape has to be right before the tag, not after.** This
already bit once: `/grants` returned port *labels* with no transport, so
port-limited grants could not be consumed at all. Fixed additively before 1.0;
after 1.0 the same fix would have needed a major version or a second field
living beside a broken one.

**Adding a configuration key is cheap, removing one is not.** A deprecated key
keeps working and warns; it disappears at the next major. The same applies to a
script flag.

**Created R-196:** the promise covered surfaces whose verification was
incomplete — the install scripts were exercised in CI only for a fresh install,
and the rollback path not at all. *Instrumented in 1.0.0* by the
`e2e-upgrade-test` work this ADR called for: CI installs the previous release,
upgrades to the tag, and rolls back, checking after each step that the service
is up, the version moved, `/etc/default/islandr` is byte-identical and both
halves of the backup returned. The database half is proven with a marker row
changed *after* the backup is taken, so a rollback that restored nothing cannot
pass. The job runs on tags only — R-196 stays open until the first RC build
turns it green.

**The console API stops being a grey area.** It is now explicitly not an
integration surface, which makes "the external API cannot do X" a reportable gap
instead of a reason to reach past it.
