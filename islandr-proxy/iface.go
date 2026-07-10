package main

import (
	"fmt"
	"os"
	"regexp"
)

// ifaceRe matches a plausible Linux network interface name: 1–15 characters
// (IFNAMSIZ is 16 including the NUL), first char alphanumeric, then alphanumerics
// plus . and -. Underscores are deliberately excluded — iproute2/systemd handle
// them poorly; prefer wg-home0 over wg_home0. Rejects whitespace, slashes, shell
// metacharacters and "..".
var ifaceRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9.-]{0,14}$`)

// resolveIface validates the operator-provided interface name, defaulting to wg0
// when unset. The interface is server-side config (from the environment), never a
// request field — validation guards against a typo'd env producing a broken wg/nft
// command, so the proxy refuses to start rather than run against a bogus interface.
func resolveIface(raw string) (string, error) {
	if raw == "" {
		return "wg0", nil
	}
	if !ifaceRe.MatchString(raw) {
		return "", fmt.Errorf("invalid interface name %q (expected 1-15 chars: letters, digits, _ . -)", raw)
	}
	return raw, nil
}

// ifaceFromEnv resolves the interface from ISLANDR_WG_INTERFACE (default wg0).
func ifaceFromEnv() (string, error) {
	return resolveIface(os.Getenv("ISLANDR_WG_INTERFACE"))
}
