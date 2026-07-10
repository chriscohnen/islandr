package main

import "testing"

func TestResolveIface(t *testing.T) {
	valid := map[string]string{
		"":         "wg0", // unset → default
		"wg0":      "wg0",
		"wg1":      "wg1",
		"wg-vpn0":  "wg-vpn0",
		"wg-home0": "wg-home0",
	}
	for in, want := range valid {
		got, err := resolveIface(in)
		if err != nil {
			t.Fatalf("resolveIface(%q) errored: %v", in, err)
		}
		if got != want {
			t.Fatalf("resolveIface(%q) = %q, want %q", in, got, want)
		}
	}

	invalid := []string{
		"wg 0",                // whitespace
		"wg0; rm -rf /",       // shell metachars
		"$(reboot)",           // command substitution
		"wg0/../etc",          // slash / traversal
		"..",                  // dots
		"wg_home0",            // underscore — iproute2/systemd dislike it; prefer '-'
		"thisnameistoolongxx", // > 15 chars (IFNAMSIZ)
	}
	for _, in := range invalid {
		if got, err := resolveIface(in); err == nil {
			t.Fatalf("resolveIface(%q) should error, got %q", in, got)
		}
	}
}
