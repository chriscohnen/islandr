# Setting up Microsoft 365 / Entra ID as the identity provider

Islandr signs users in through an OIDC provider and keeps the local
`ISLANDR_ADMIN_USER` account only as a recovery path. This page covers the
Entra ID side: what to register, which permissions Islandr actually needs, and
which of the setup mistakes produce an error message that does not name them.

Everything here is configured at runtime in **Settings → Identity**. Nothing
goes into `application.properties`, and no restart is involved.

## What Islandr asks for

Four delegated scopes, sent on every login:

| Scope | Why |
|---|---|
| `openid` | Required for OIDC. Produces the ID token Islandr verifies against the tenant's JWKS. |
| `profile` | The display name shown in the user list. |
| `email` | The e-mail address. This is the account's identity in Islandr and what the allowed-domains filter matches on. |
| `User.Read` | Reads `/me` and `/me/photo/$value` — the user's own profile and their MS365 photo, used as the second step of the avatar fallback chain. |

All four are **delegated** permissions, not application permissions. Islandr
never acts without a signed-in user, so no application permission and no
directory-wide role is needed. The app registration reads one user's own
profile with that user's own token.

A fifth scope, `offline_access`, is also requested. Islandr does not implement
a refresh-token flow, so it is currently unused — it is asked for now so that
adding one later does not require every tenant to re-consent. If your tenant
allows ordinary user consent, an admin who did not grant it will simply never
notice. If user consent is disabled tenant-wide, grant it along with the
others, or logins fail on the missing scope.

## Registering the application

In the Entra admin center, under **Identity → Applications → App registrations
→ New registration**:

1. **Name** — free choice, it is only shown to your own admins.
2. **Supported account types** — *Accounts in this organizational directory
   only*. Islandr's endpoints are tenant-scoped, so a multi-tenant
   registration buys nothing.
3. **Redirect URI** — platform **Web**, and the URL exactly as the Identity
   page shows it (there is a copy button next to it):

   ```
   https://<your-hub>/api/v1/auth/oidc/microsoft/callback
   ```

   It must match character for character, including the scheme and any
   non-default port. Entra compares the string, not the host.

Then, under **Certificates & secrets → New client secret**, create a secret and
note its expiry — Entra caps it at 24 months, and an expired secret shows up as
a login failure, not as a warning anywhere in Islandr.

> **The one mistake worth calling out.** The secrets table has two columns,
> **Value** and **Secret ID**, and both look like opaque identifiers. Islandr
> needs the **Value**. It is shown only once, at creation; navigate away and it
> is gone for good and you create a new secret. The Secret ID is a GUID, so a
> value that looks like `11111111-2222-3333-4444-555555555555` is the wrong
> one. Islandr refuses a UUID-shaped secret on save and says which of the two
> it wants; before 0.22.0 it accepted it and the mistake surfaced much later as
> a login failure that did not mention the secret at all.

## The three values Islandr wants

From the app registration's **Overview** page:

| Islandr field | Entra field |
|---|---|
| Client ID | *Application (client) ID* |
| Tenant ID | *Directory (tenant) ID* — the German portal calls it *Mandanten-ID* |
| Client Secret | the **Value** of the secret you just created, not its Secret ID |

**Allowed domains** is Islandr's own filter, not an Entra setting. Left empty,
every account Microsoft lets through the consent may sign in. Set it to your
own domains when the tenant contains guest accounts you do not want in the VPN.

## Admin consent

Without consent, each user gets a permission dialog on their first login. The
Identity page offers an **admin consent link** that accepts the permissions
once for the whole tenant.

After consent, Microsoft sends the browser back to Islandr, which reports the
outcome on the Identity page: *Consent granted*, or *Consent was declined* if
you cancelled. Nothing else is needed — the return carries no authorization
code and is not a login.

> Islandr up to 0.21.0 answered that return with
> `state mismatch (CSRF protection)`, because it ran the consent redirect
> through the login callback's CSRF check. The consent itself had succeeded;
> only the report was wrong. Fixed in 0.22.0
> ([#81](https://github.com/chriscohnen/islandr/issues/81)).

**Consent may not be needed at all.** If the app registration already shows the
permissions as granted under **API permissions**, the link has nothing left to do.

## Checking the configuration

The Identity page has a **Test configuration** button next to the consent link.
It checks the four values separately and names the one at fault, which is the
difference between "something is wrong" and "the secret expired":

| Field | How it is checked | What a failure means |
|---|---|---|
| Tenant ID | Fetches the tenant's discovery document. No credentials involved. | Microsoft does not know this directory. |
| Client ID | Probes the authorize endpoint. | `AADSTS700016` — the application is not in this tenant. |
| Client secret | Requests a `client_credentials` token. | `AADSTS7000215` — wrong secret (usually the Secret ID). `AADSTS7000222` — expired. |
| Redirect URI | Same probe, different code. | `AADSTS50011` — the URI Islandr sends is not registered on the app. |

A field is reported as *not checked* when an earlier one already failed: with a
tenant that does not resolve, nothing can be said about the client ID.

These are the only requests Islandr makes to Microsoft outside a login, and
they happen when you press the button — never on a timer.

## When a login fails

Islandr surfaces the provider's own error, which is usually the fastest way in.
The Entra codes that come up in practice:

| Symptom | Cause |
|---|---|
| `AADSTS7000215: Invalid client secret provided` | The Secret ID was pasted instead of the secret Value. Islandr now refuses a UUID-shaped secret at input time and says so. |
| `AADSTS7000222: The provided client secret keys are expired` | The secret has expired. Create a new one and copy its Value. |
| `AADSTS700016: Application ... not found in the directory` | Client ID and Tenant ID belong to different tenants, or the client ID is wrong. |
| `AADSTS50011: The redirect URI ... does not match` | The registered redirect URI differs from the one Islandr sends. Copy it from the Identity page rather than typing it. |
| `AADSTS900023: Specified tenant identifier is neither a valid DNS name nor a valid external domain` | The Tenant ID field holds something other than the directory GUID (a domain name works too, but not an application ID). |
| Sign-in succeeds, but the account cannot log in to Islandr | The account is disabled in Islandr, or its e-mail domain is not in **Allowed domains**. |

The client secret is never read back into the form. Leaving the field empty on
an edit keeps the stored one; to replace it, paste the new value.

## Related

- [ADR-0011](../adr/0011-process-privilege-model.md) — the privilege model Islandr runs under
- [install.md](../install.md) — the rest of the deployment
