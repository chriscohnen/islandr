# ADR-0009 — License: EUPL-1.2

**Status:** Accepted
**Date:** 2026-06-02
**Deciders:** Christian Cohnen

## Context

Islandr is a self-hosted WireGuard access management tool intended for small teams. The project will be published as open source. Three candidate licenses were evaluated:

| Criterion | MIT | GPL-3.0 | EUPL-1.2 |
|---|---|---|---|
| Copyleft (forks stay open) | − | + | + |
| European legal standing | 0 | 0 | + |
| Compatible with GPL/AGPL forks | + | + | + |
| SaaS loophole closed | − | − | + (network use = distribution) |
| Patent grant explicit | − | + | + |
| Permissive for integrators | + | − | 0 |

EUPL-1.2 is the only OSI-approved license drafted under EU law and explicitly enforceable in all EU member states. Its Appendix lists compatible licenses (GPL-2, GPL-3, AGPL-3, MPL-2, LGPL, etc.), so downstream forks can re-license under those terms — avoiding the "license island" problem of strict copyleft.

The SaaS/network-use clause matters here: a hosting provider running Islandr for customers must publish their modifications, unlike under GPL-3.0 where they could keep them private.

MIT was ruled out because it places no obligation on forks to stay open, which conflicts with the project goal of keeping derivative VPN management tools publicly available.

## Decision

License Islandr under **EUPL-1.2 only**.

- `LICENSE` at the repository root contains the full EUPL-1.2 English text.
- Copyright holder: Christian Cohnen, 2026.
- No dual-licensing or "or later" clause — EUPL-1.2 only, to keep the terms stable.

## Pugh Matrix

Evaluated against the project quality goals: openness, European legal clarity, and protection against proprietary forks.

| Criterion | MIT | GPL-3.0 | **EUPL-1.2** |
|---|---|---|---|
| Forks stay open | −1 | +1 | **+1** |
| EU legal enforceability | 0 | 0 | **+1** |
| Network-use copyleft | −1 | −1 | **+1** |
| Compatible with major FOSS licenses | +1 | 0 | **+1** |
| Integrator friction | +1 | −1 | **0** |
| **Total** | **0** | **−1** | **+4** |

## Consequences

- **Positive:** Forks and hosted deployments must publish their modifications. The license is natively enforceable in German courts.
- **Positive:** Compatible with GPL/AGPL, so Islandr code can be incorporated into GPL-licensed tooling if needed.
- **Neutral:** Integrators embedding Islandr into a larger proprietary product must publish the Islandr-derived portion under EUPL-1.2. This is intentional.
- **Risk R-035 (inferred):** If a future contributor adds a dependency under an EUPL-incompatible license, the combined work would be in conflict. Mitigate by checking dependency licenses before merging.

## Open questions

### Freemium path

A future hosted or managed Islandr offering ("Islandr Cloud") is not ruled out. EUPL-1.2 does not prevent the copyright holder (Christian Cohnen) from offering a commercial variant alongside the open-source release — the copyright owner is exempt from their own copyleft. Two concrete paths exist if that becomes relevant:

- **Open-core:** Keep the core EUPL-1.2, add proprietary modules (e.g. SSO connectors, multi-hub orchestration) in a separate, closed repository. The open-source boundary must be drawn at a clean API seam; mixing EUPL and proprietary code in the same binary is legally fragile.
- **Dual-license:** Offer EUPL-1.2 for self-hosters and a commercial license for integrators who cannot comply with copyleft. This is the MySQL/Elastic pattern. Requires a CLA (Contributor Licence Agreement) so that contributor code can be re-licensed — otherwise each contributor retains copyright over their patch and the dual-license cannot be extended to that code.

Neither path requires changing the current `LICENSE` file. The decision to pursue either is deferred; if it becomes concrete, a new ADR should document the business model and CLA requirement.

### GitHub free-tier eligibility

EUPL-1.2 is OSI-approved, which is the baseline requirement for most GitHub "public repo" benefits (unlimited Actions minutes on public repos, Dependabot, etc.). However, two specific programs are more restrictive:

- **GitHub Copilot free tier (2025+):** GitHub's terms require either MIT, Apache-2.0, or another explicitly listed permissive license for the AI-assisted PR/suggestion features. EUPL-1.2 is copyleft; it is unlikely to qualify. Verify against GitHub's current terms before relying on this.
- **GitHub Actions minutes:** Public repositories on GitHub get unlimited free Actions minutes regardless of license. The CI pipeline in `.github/workflows/ci.yml` is unaffected. No concern here.

The current setup (no Copilot dependency, CI via public-repo Actions) is unaffected by this ambiguity. If GitHub Copilot integration becomes relevant for contributors, check the then-current terms; switching to a permissive license for that reason alone would be disproportionate.

### Contribution model

The project does not currently accept external pull requests. All development is by the copyright holder. This avoids the CLA complexity mentioned above and keeps the dual-license option open. If the project opens to external contributions later, a CLA or DCO (Developer Certificate of Origin) must be introduced before merging the first external patch.
