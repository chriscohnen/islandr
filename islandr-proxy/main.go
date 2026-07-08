// Command islandr-proxy is the host-side privileged helper for the containerised
// islandr app. It listens on a systemd-activated Unix socket, accepts one
// line-delimited JSON request per connection, and runs a fixed allowlist of
// wg/nft commands as argument vectors. See docs/adr/0012-docker-socket-proxy.md.
package main

import (
	"log"
)

// Server-side constants. Neither is ever taken from a request: the interface is
// fixed and the ruleset lives at a known path in the shared data volume.
const (
	wgInterface = "wg0"
	rulesetPath = "/var/lib/islandr/ruleset.nft"
)

func main() {
	log.SetFlags(0)
	log.SetPrefix("islandr-proxy: ")

	cfg := Config{
		Iface:       wgInterface,
		RulesetPath: rulesetPath,
		// short-lived preshared-key files are written here, not in a world dir.
		RuntimeDir: runtimeDir(),
	}

	l, err := listenFromSystemd()
	if err != nil {
		log.Fatal(err)
	}
	defer l.Close()

	log.Print("serving on systemd-activated socket")
	if err := serve(l, NewHandler(osExec{}, cfg)); err != nil {
		log.Fatalf("serve: %v", err)
	}
}
