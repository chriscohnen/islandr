# ADR-0002 — Vue 3 frontend without the npm toolchain

**Status:** Accepted (with caveats — see Open question)
**Date:** 2026-05-30
**Deciders:** Christian Cohnen

## Context

The Admin Console and Self-Service Portal are non-trivial frontends — sidebar shell, datatable with row drawer, ACL matrix, 3-step wizard with QR code, dark/light theming via CSS variables, two surfaces sharing one component family. Built as a real SPA, not server-rendered HTML.

The product is a single-binary self-hosted backend (see [ADR-0001](0001-quarkus-backend.md)). The frontend ships *inside* the Quarkus binary as static assets under `META-INF/resources/`. There is no separate frontend deploy target.

The team's strong preference, stated in the project brief, is: **avoid an npm-heavy toolchain.** Reasons stated:

- npm/Node is a second runtime to install, version-manage, and security-patch alongside the JVM/GraalVM.
- `node_modules` is a known weak link for supply-chain attacks; minimizing the dependency tree is desirable.
- Iteration cycles slow down when every change runs through Vite + 200 npm packages.
- The team doesn't want to maintain frontend tooling configs (`vite.config.ts`, `tsconfig.json`, ESLint, Prettier, …) as a permanent overhead.

But: "no npm at all" and "ship a production SPA" pull in opposite directions. Vue 3 itself can run from a CDN ESM bundle (`vue.esm-browser.js`) with `<script type="importmap">` and zero build step. That works in dev. It also works in production, but with caveats: no tree-shaking, no minification of app code, no asset hashing for cache busting, no `.vue` SFC compilation.

The constraint, restated honestly: **minimize npm surface area; accept a small, optional optimization step at release time.**

## Decision

Use **Vue 3 with the Composition API**, served as plain ES modules. Production uses a small, optional asset-optimization step — not a full Node toolchain.

**Development:**
- Vue + Vue Router + Pinia loaded via `<script type="importmap">` pointing at a self-hosted local copy (not a CDN, per N-01).
- Components written as `.js` files using `defineComponent({ template: \`...\` })` — no `.vue` SFCs, no template compiler at runtime.
- CSS imported via `@import` in a root `app.css` that pulls in the design system's `colors_and_type.css` and `components.css` verbatim.
- Served by Quarkus Live Reload in dev (`quarkus dev` watches `src/main/resources/META-INF/resources/`).

**Production:**
- A single esbuild invocation (no `package.json` for the app; esbuild is run as a versioned binary downloaded once, not via npm) bundles and minifies the JS, hashes filenames, and emits a manifest.
- The bundled assets land in `src/main/resources/META-INF/resources/assets/` and are served by Quarkus.
- A short Maven plugin step (`exec-maven-plugin`) runs esbuild during `package`. CI caches the esbuild binary.

The point: **one binary tool (esbuild), no `node_modules` directory, no transitive dependency tree.**

## Alternatives considered (Pugh Matrix)

Baseline: **"Plain Vue + standalone esbuild"** (the decision above).

| Criterion (weight)            | Plain Vue + esbuild (baseline) | Quarkus Quinoa (full Vite) | Vue CDN only, no build | HTMX + server-rendered |
|-------------------------------|--------------------------------|-----------------------------|--------------------------|--------------------------|
| Avoids npm dep tree (5)       | 0                              | -1                          | +1                       | +1                       |
| Fits the design (SPA-shaped) (4) | 0                          | +1                          | 0                        | -1                       |
| Iteration speed (4)           | 0                              | 0                           | +1                       | -1                       |
| Production asset quality (3)  | 0                              | +1                          | -1                       | 0                        |
| `.vue` SFC support (2)        | 0                              | +1                          | -1                       | n/a                      |
| Long-term maintenance burden (3) | 0                          | -1                          | +1                       | +1                       |
| Team familiarity with Vue (3) | 0                              | 0                           | 0                        | -1                       |
| **Weighted total**            | **0**                          | **+2**                      | **+1**                   | **−4**                   |

Honest reading of the matrix:

- **Quinoa** scores +2 because it solves more problems out of the box (Vite handles `.vue` SFCs, hot module replacement, asset hashing). But the −5 weight on "avoids npm dep tree" matters disproportionately for *this project's stated values*, so the matrix score is misleading without that framing. Quinoa pulls a full Vite + npm tree at build time. The constraint is the constraint.
- **Vue CDN only, no build** scores +1 and is tempting. The downside is real: no minification of app code (the SPA itself, not Vue) means a ~3× larger payload over the wire and worse first-paint on dim networks. For an internal admin tool that's fine; for the Self-Service Portal a non-technical user opens on hotel Wi-Fi, less so.
- **HTMX + server-rendered** scores −4 mostly on "fits the design": the ACL matrix and the 3-step wizard with live QR/handshake polling are genuinely SPA-shaped. HTMX would force compromises against the design handoff.

The baseline wins because it threads the needle: respects the no-npm-tree constraint, ships a respectable production bundle, and stays close to standard Vue.

## Consequences

**Positive**

- Zero `node_modules`. esbuild is a single binary, versioned in `tools/` or downloaded by the Maven plugin.
- Dev loop: edit `.js` file → Quarkus Live Reload pushes it → browser sees it. No Vite watcher.
- Same Quarkus dev server handles API and assets — no port juggling, no proxy config.
- Production bundle is hashed, minified, gzip/brotli-friendly.

**Risks created**

- **R-010** — No `.vue` SFC support means components are `defineComponent({ template: \`...\` })` with template strings. Loses IDE template-language support unless Volar is configured for template literals. Mitigation: consistent Vue-style guide; consider Vite-Vue path in v2 if SFC ergonomics become blocking.
- **R-011** — esbuild does not run Vue's template compiler. Templates are compiled at runtime by `vue.esm-browser.js` (which includes the compiler). That's slightly slower at first paint and slightly larger than the runtime-only build. Acceptable for this scope, but counts as a known cost.
- **R-012** — Without a typical npm ecosystem, pulling in a third-party Vue component library (PrimeVue, Naive UI) becomes awkward. Mitigation: the Islandr design system defines its own component primitives — Islandr ports them rather than pulling a library. The trade was already implicit in the design choice.
- **R-013** — esbuild itself is an npm package upstream, even if we use the standalone binary. The supply-chain risk reduction is "smaller surface", not "zero surface". State this honestly.

**Accepted trade-offs**

- Slower first paint than a full Vite build (no template precompilation, no tree-shaken Vue).
- No first-class TypeScript. The app is JS with JSDoc type annotations where useful.
- Hot-module-replacement is replaced by full-page reload via Quarkus Live Reload. Slower iteration than Vite HMR, but the gap is smaller than it sounds for component-level work.

**Open question**

OQ-2 in [docs/prd.md](../prd.md) — confirm the esbuild standalone path actually delivers the asset quality we need before locking in. If first-paint on the Portal is unacceptable, revisit with Quinoa or a vendored Vite setup. This ADR is Accepted with the caveat that a spike validates the build pipeline before the first user-visible release.

## References

- [Vue 3 ESM build via importmap](https://vuejs.org/guide/quick-start.html#using-vue-from-cdn)
- [Quarkus Quinoa](https://quarkiverse.github.io/quarkiverse-docs/quarkus-quinoa/dev/) — the path not taken; revisit if this ADR is reversed.
- [esbuild](https://esbuild.github.io/)
- [docs/adr/0001-quarkus-backend.md](0001-quarkus-backend.md) — single-binary deployment is the reason this constraint exists.
