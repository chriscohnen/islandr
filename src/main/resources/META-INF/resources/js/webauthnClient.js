// Browser-side half of the two WebAuthn ceremonies (ADR-0028, issue #67).
// The server side (WebAuthnService, vertx-auth-webauthn) speaks the engine's
// own JSON convention, not the newer W3C PublicKeyCredential.toJSON() one:
// challenge/user.id/credential ids travel as base64url strings, and the
// browser's binary ArrayBuffers have to be converted by hand on the way out
// and back in. Both directions live here so LoginView and SettingsView never
// touch a CBOR byte.

function b64urlToBuf(b64url) {
  const b64 = b64url.replace(/-/g, "+").replace(/_/g, "/");
  const pad = b64.length % 4 === 0 ? "" : "=".repeat(4 - (b64.length % 4));
  const bin = atob(b64 + pad);
  const buf = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) buf[i] = bin.charCodeAt(i);
  return buf.buffer;
}

function bufToB64url(buf) {
  const bytes = new Uint8Array(buf);
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Whether this browser can do WebAuthn at all — checked before ever asking
 *  the server, so an unsupported browser never sees a button that would just
 *  throw when clicked. */
export function browserSupportsWebauthn() {
  return typeof window !== "undefined" && !!window.PublicKeyCredential;
}

/** The server's challenge JSON, with challenge / user.id / excludeCredentials
 *  ids turned back into the ArrayBuffers navigator.credentials.create() needs. */
function decodeCreationOptions(options) {
  const out = { ...options, challenge: b64urlToBuf(options.challenge) };
  if (options.user) out.user = { ...options.user, id: b64urlToBuf(options.user.id) };
  if (options.excludeCredentials) {
    out.excludeCredentials = options.excludeCredentials.map((c) => ({ ...c, id: b64urlToBuf(c.id) }));
  }
  return out;
}

function decodeRequestOptions(options) {
  const out = { ...options, challenge: b64urlToBuf(options.challenge) };
  if (options.allowCredentials) {
    out.allowCredentials = options.allowCredentials.map((c) => ({ ...c, id: b64urlToBuf(c.id) }));
  }
  return out;
}

/** The credential navigator.credentials.create()/get() returns, re-encoded to
 *  the base64url JSON shape WebAuthnService#verify expects. */
function encodeCredential(credential, kind) {
  const response = { clientDataJSON: bufToB64url(credential.response.clientDataJSON) };
  if (kind === "create") {
    response.attestationObject = bufToB64url(credential.response.attestationObject);
  } else {
    response.authenticatorData = bufToB64url(credential.response.authenticatorData);
    response.signature = bufToB64url(credential.response.signature);
    // Present-but-not-a-String fails the engine's own validation, so a
    // non-resident assertion (no userHandle) must omit the key rather than
    // send it as null.
    if (credential.response.userHandle) {
      response.userHandle = bufToB64url(credential.response.userHandle);
    }
  }
  return { id: credential.id, rawId: bufToB64url(credential.rawId), type: credential.type, response };
}

async function postJson(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    // Always a body, even {} — the endpoint's own class-level @Consumes
    // rejects a bodyless POST with 415 before the handler runs (same
    // reasoning as PortScanResource#cancelScan's DELETE choice elsewhere in
    // this codebase, just the other fix: here the body is cheap to send).
    body: JSON.stringify(body === undefined ? {} : body),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(text || "HTTP " + res.status);
  }
  return res.status === 204 ? null : res.json();
}

/** Full enrol ceremony: admin session required server-side, same as any
 *  other account change. Resolves to nothing on success. */
export async function registerSecurityKey(label) {
  const options = await postJson("/api/v1/auth/webauthn/register/challenge", { label: label || null });
  const credential = await navigator.credentials.create({ publicKey: decodeCreationOptions(options) });
  await postJson("/api/v1/auth/webauthn/register/verify", {
    label: label || null,
    response: encodeCredential(credential, "create"),
  });
}

/** Full sign-in ceremony. Resolves to nothing on success — the server has
 *  already set the session cookie, same as a password login. */
export async function loginWithSecurityKey() {
  const options = await postJson("/api/v1/auth/webauthn/login/challenge");
  const credential = await navigator.credentials.get({ publicKey: decodeRequestOptions(options) });
  await postJson("/api/v1/auth/webauthn/login/verify", {
    response: encodeCredential(credential, "get"),
  });
}
