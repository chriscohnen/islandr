# Roadmap

Where Islandr is heading. The live backlog is the
[GitHub issue tracker](https://github.com/chriscohnen/islandr/issues); the
planned items below are read from it each time this site is rebuilt, so they
cannot drift from it. A 👍 or a comment on an issue is how you signal interest
and shape priority.

## Where things stand

<!-- roadmap:current-release -->

What each release changed is in the
[changelog](https://github.com/chriscohnen/islandr/blob/main/CHANGELOG.md),
with binaries and checksums on the
[releases page](https://github.com/chriscohnen/islandr/releases). Since 1.0,
semantic versioning covers the external API, the configuration, the install
and update scripts and the on-disk layout — upgrading is meant to stop being
an event.

## Direction

**Edge hubs** are the next larger piece of work: a second WireGuard hub at a
remote site, so that a distant office keeps its traffic local instead of
sending it through the central hub. Since 1.2 the model is in place — a
device belongs to exactly one hub, and its configuration points at that hub.
What does not exist yet is the edge itself: software that fetches its peers
and firewall rules from the central hub over the API and enforces them
locally. Until it does, edge hubs are groundwork, not something to deploy.

In between, smaller releases keep fixing what real installations run into.

## Planned

<!-- roadmap:issues -->

## Release lines

Thematic milestones group the work:

- **v1 — Core** — everything required for 1.0, including production Docker.
  Shipped with 1.0.0.
- **v2 — Usability & convenience** — quality-of-life features after the core.
- **v3 — Operations** — operating Islandr at scale and inside existing
  monitoring.

---

<!-- roadmap:generated-on -->
