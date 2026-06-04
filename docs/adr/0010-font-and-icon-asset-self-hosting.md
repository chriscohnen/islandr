# ADR-0010 — Font and icon asset self-hosting

**Status:** Accepted
**Date:** 2026-06-04
**Deciders:** Christian Cohnen

---

## Context

Islandr uses two typefaces in the Admin Console and Self-Service Portal:

- **IBM Plex Sans** — UI / body text (Apache 2.0)
- **IBM Plex Mono** — wordmark, IPs, keys, code snippets (Apache 2.0)

Both are currently loaded via the Google Fonts CDN (`fonts.googleapis.com` / `fonts.gstatic.com`).
Icons are inline SVG rendered from a hand-rolled `Icons.jsx` component — no external icon CDN
is involved.

Two independent risks justify a policy change:

### 1. Data-privacy risk (GDPR)

Every Google Fonts request transmits the visitor's IP address to Google servers in the US.
The Court of Justice of the EU (CJEU) and German courts (LG München I, January 2022) have
ruled this a GDPR violation when done without prior, explicit consent. A consent banner is
not a practical remedy for a tool whose Admin Console and Self-Service Portal are used without
any marketing-consent flow.

The Islandr app is self-hosted on a private VM with a small, known user base — the practical
risk is lower than a public SaaS product, but the legal exposure is identical: the GDPR does
not distinguish by deployment scale. Self-hosted operators who open the portal to employees
carry the same obligation.

### 2. Font licensing risk

Several widely used typefaces (Arial, Calibri, Times New Roman, Segoe UI) are proprietary
Microsoft or Monotype property. Using them as embedded webfonts — even by declaring them in
`font-family` stacks — without a commercial web licence is an infringement. This applies even
when the font is installed on the end-user's OS.

The fonts Islandr uses (IBM Plex Sans, IBM Plex Mono) are already Apache 2.0-licensed and free to
self-host. The risk here is not the current choice but the absence of a documented policy that
prevents a future contributor from introducing a proprietary font.

---

## Decision

**Self-host all fonts. Use open-source-licensed fonts only. No external font or icon CDN.**

Concretely:

1. **IBM Plex Sans + IBM Plex Mono** (app): download `.woff2` files, serve from
   `src/main/resources/META-INF/resources/fonts/`, load via `@font-face` in `tokens.css`.
   Remove the Google Fonts `<link>` tags from `index.html`.

2. **Icons**: already inline SVG — no change needed. The policy explicitly forbids adding
   an external icon CDN (Font Awesome CDN, Material Icons CDN, etc.) in the future.

3. **Permitted font licence types**: SIL Open Font License (OFL), Apache 2.0, MIT.
   Any other licence requires explicit legal review before use.

4. **Explicitly prohibited**:
   - Embedding or serving proprietary OS fonts (Arial, Calibri, Segoe UI, Helvetica Neue,
     San Francisco / -apple-system) as actual font files. They may appear in CSS fallback
     stacks only — never as `src: url(...)` in `@font-face`.
   - Loading any asset (font, icon, image) from a third-party CDN that receives the visitor's
     IP address without a GDPR-compliant consent mechanism.
   - Using a font whose licence has not been verified, even if it appears "free."

---

## Alternatives considered (Pugh Matrix)

Scoring vs. **self-hosting** as baseline. `+1` better, `0` equal, `-1` worse.

| Criterion                        | Self-host (baseline) | Google Fonts CDN | CDN + Consent Banner |
|----------------------------------|:--------------------:|:----------------:|:--------------------:|
| GDPR compliance (no consent req) |          0           |        −1        |          0           |
| Licence clarity                  |          0           |         0        |          0           |
| Air-gapped / offline operation   |          0           |        −1        |         −1           |
| Performance (no 3rd-party DNS)   |          0           |        −1        |         −1           |
| Implementation effort            |          0           |        +1        |         −1           |
| Maintenance overhead             |          0           |        +1        |         +1           |
| **Total**                        |        **0**         |      **−1**      |        **−1**        |

CDN + Consent Banner scores the same as CDN alone on the relevant axes: it eliminates the
GDPR problem but re-introduces it for every user who hasn't consented yet (i.e. first load),
and does nothing for air-gapped environments. It also adds significant implementation and
UX complexity for what is primarily an admin tool.

---

## Consequences

### Positive

- **GDPR compliance**: no IP address leakage to third-party servers on any page load.
- **Air-gapped operation**: the app renders correctly in isolated networks — relevant for
  corporate environments that restrict outbound internet from the hub VM.
- **Licence clarity**: the font inventory is fully documented and auditable; no surprise
  licensing claim can arise from fonts served by Islandr.
- **LCP improvement**: eliminating cross-origin font requests removes a render-blocking
  round-trip; fonts load from the same origin as the HTML.
- **Operational independence**: Google Fonts outages or API changes cannot affect availability.

### Negative

- **Deployment size**: `.woff2` files for IBM Plex Sans (4 weights × 2 styles ≈ ~300 KB),
  IBM Plex Mono (3 weights ≈ ~51 KB) add approximately 155 KB to the served assets
  (Latin1 subset only). With HTTP/2 and `Cache-Control: immutable`, this is a
  one-time cost per client.
- **Maintenance**: font updates must be done manually (download new `.woff2`, commit).
  Security patches to font files are rare but not impossible.
- **Design constraint**: the policy limits new typeface choices to OFL/Apache-licensed fonts.
  This is a deliberate restriction, not an oversight.

### Risks

- **R-035** (carried forward from ADR-0009): OFL and Apache 2.0 fonts explicitly permit
  commercial use — there is no conflict if Islandr becomes a commercial or SaaS product.
  The constraint is only relevant if a future commercial variant wants to adopt a
  proprietary typeface (e.g. a Monotype or Adobe font); that would require a separate
  commercial web licence purchase, which is standard procurement, not a legal trap.
- **R-036** (new): font subsetting not yet applied — full character sets are served even
  though the UI is EN/DE only. Consider subsetting Latin glyphs to reduce transfer size
  in a follow-up task.

---

## Implementation checklist

- [x] Download IBM Plex Sans woff2 (weights 400, 500, 600, 700) from
      [github.com/IBM/plex](https://github.com/IBM/plex) (Apache 2.0) — Latin1 subset only
- [x] Download IBM Plex Mono woff2 (weights 400, 500, 600) from same source — Latin1 subset only
- [x] Place under `src/main/resources/META-INF/resources/fonts/`
- [x] Add `@font-face` declarations to `tokens.css`, remove Google Fonts `<link>` from `index.html`
- [ ] Verify rendering in Chrome, Firefox, Safari (macOS + iOS)
- [x] Font files committed — not gitignored
