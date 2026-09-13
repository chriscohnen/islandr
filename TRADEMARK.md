# Trademark policy

The EUPL-1.2 covers Islandr's **code**. It does not cover its **name** or its
**logo**. That is not a restriction added on top of the licence — it is written
into the licence itself, under *Legal Protection*:

> This Licence does not grant permission to use the trade names, trademarks,
> service marks, or names of the Licensor, except as required for reasonable and
> customary use in describing the origin of the Work and reproducing the content
> of the copyright notice.

Because that sentence sits in the middle of a document nobody reads end to end,
this page says the same thing in a form you can act on.

"Islandr", the Islandr wordmark and the constellation mark are marks of
Christian Cohnen. They are not registered at the time of writing.

## What you can do without asking

- **Say that your thing works with Islandr.** "Works with Islandr", "for
  Islandr", "an Islandr plugin", "based on Islandr" — describing a factual
  relationship is exactly the "reasonable and customary use" the licence allows.
- **Redistribute Islandr unchanged**, branding intact. Packaging it for a distro,
  a NAS app store or a container registry needs no permission.
- **Use screenshots** in articles, reviews, comparisons, talks and documentation,
  including critical ones.
- **Link to the project** and use the name in prose when writing about it.

No permission request, no attribution beyond what the licence already requires.

## What needs a different name

- **A fork.** Take the code — that is what the licence is for. Give it your own
  name and your own logo. This is ordinary practice, not a hostile rule: Debian's
  Firefox rebuild shipped as Iceweasel for the same reason. Renaming is not the
  same as erasing where it came from — see the next section.
- **A hosted service.** Running Islandr as a service for others is allowed by the
  licence (with the source obligations it carries), but the service cannot be
  called Islandr or carry its logo.
- **Anything that suggests endorsement.** Do not present a modified build,
  a service, or a product as official, certified, approved or affiliated.
- **Domain names, app names, social handles and company names** built around the
  mark.

## What a fork must still do

Changing the name does not make the code yours, and this policy is not a licence
to remove the origin. The EUPL's *Attribution right* obliges anyone who
distributes a modified version to:

- **keep every copyright, patent, trademark and licence notice intact** — the
  `Copyright (c) Christian Cohnen` line stays, including in your renamed build;
- **ship a copy of those notices and of the Licence** with every copy;
- **carry prominent notices that the work has been modified, and when**;
- **publish the source** of the modified version under the EUPL.

So the two rules point in opposite directions on purpose, and both hold:

> Do not call it Islandr. Do say it is derived from Islandr.

Stating the origin is not merely tolerated — the trademark carve-out in the
licence exists precisely so you can, and the attribution obligation means you
must not hide it. "Foobar, a fork of Islandr" is correct on both counts.
"Foobar", with the notices stripped and no mention of where it came from, is a
licence violation.

## The logo files

`logo.svg` and `favicon.svg` ship inside the source tree, so the licence's
copyright grant reaches them like any other file. The trademark does not. In
practice: you may keep them in an unmodified redistribution, and you may not use
them as the identifying mark of a fork, a service or a product of your own —
even though you have a copyright licence to the file itself.

If that combination sounds odd, it is the normal state of affairs for any open
source project that has a logo. Copyright governs the file; trademark law
governs what a mark is allowed to identify.

## What is not claimed

The visual design is not. The dark palette, the starfield, the island metaphor
and the token set in `css/tokens.css` are licensed with the rest of the code, and
you may build whatever you like on them. What is protected is the name and the
logo as indicators of origin — not an aesthetic.

## Other people's marks

WireGuard® is a registered trademark of Jason A. Donenfeld. Islandr is an
independent project, not affiliated with or endorsed by the WireGuard project.
Microsoft 365, Entra ID and Google Workspace are marks of their respective
owners and are named here only to describe interoperability.

## Questions

Anything this page does not answer, or a use you think is reasonable and is not
listed: open an issue or write to chris@chriscohnen.de. Asking is free and the
answer is usually yes.
