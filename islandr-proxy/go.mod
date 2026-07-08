// islandr-proxy — host-side privileged helper for the containerised islandr app.
// Speaks the line-delimited JSON protocol of de.chriscohnen.islandr.proxy.ProxyClient
// over a systemd-activated Unix socket and executes a fixed allowlist of wg/nft
// commands as argument vectors. Zero third-party dependencies on purpose (ADR-0012):
// the trusted computing base is the Go stdlib plus this source, nothing to vet.
module github.com/chriscohnen/islandr/islandr-proxy

go 1.20
