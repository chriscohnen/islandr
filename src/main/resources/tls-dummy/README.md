# Dummy TLS placeholder certificate

`dummy-cert.pem` / `dummy-key.pem` are a **self-signed, publicly-known placeholder**
key pair (ADR-0015), generated once via:

```
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout dummy-key.pem -out dummy-cert.pem -days 7300 \
  -subj "/CN=islandr-dummy-placeholder/O=islandr (replace this certificate)" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

They exist only so a fresh install can bind HTTPS immediately, before the admin
uploads a real certificate. **This key pair is not a secret** — it ships in the
public repository and every islandr install starts with the identical key.
Browsers correctly show a "not private" warning for it (self-signed, and the
subject name says so). Settings surfaces a persistent "TLS not yet configured"
banner while it is active — the same pattern used for the WireGuard
`PLACEHOLDER_SERVER_PUBKEY` seed value.

Never treat a deployment still running this certificate as configured for
production HTTPS.
