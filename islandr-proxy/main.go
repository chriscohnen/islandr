// Command islandr-proxy is the host-side privileged helper for the containerised
// islandr app. It listens on a systemd-activated Unix socket, accepts one
// line-delimited JSON request per connection, and runs a fixed allowlist of
// wg/nft commands as argument vectors. See docs/adr/0012-docker-socket-proxy.md.
package main

import (
	"log"
)

// The ruleset lives at a known path in the shared data volume; never from a request.
const rulesetPath = "/var/lib/islandr/ruleset.nft"

func main() {
	log.SetFlags(0)
	log.SetPrefix("islandr-proxy: ")

	// The interface is operator config (ISLANDR_WG_INTERFACE, default wg0); it must
	// match the container's islandr.wg.interface. Refuse to start on a bogus value.
	iface, err := ifaceFromEnv()
	if err != nil {
		log.Fatal(err)
	}

	cfg := Config{
		Iface:       iface,
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
