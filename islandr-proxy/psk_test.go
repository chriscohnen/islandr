package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Where the short-lived preshared-key file goes (psk-apparmor-path).
//
// wg never accepts a preshared key as an argument — argv is world readable, so
// it reads one from a file. Ubuntu 26.04 ships an enforcing AppArmor profile
// for /usr/bin/wg whose only file rule is "file rw @{etc_rw}/wireguard/{,**}",
// and which denies the binary dac_override and dac_read_search. Measured on
// the image: a key under /run, /tmp, /var/tmp or /root is refused even for
// root, with no error beyond "fopen: Permission denied". So the file has to be
// born under /etc/wireguard.

func TestWritePskUsesThePskDir(t *testing.T) {
	dir := t.TempDir()
	h := &Handler{cfg: Config{PskDir: dir, RuntimeDir: t.TempDir()}}

	path, err := h.writePsk("a-key")
	if err != nil {
		t.Fatalf("writePsk: %v", err)
	}
	defer os.Remove(path)

	if got := filepath.Dir(path); got != dir {
		t.Errorf("key written to %s, want it in %s", got, dir)
	}
	b, err := os.ReadFile(path)
	if err != nil || string(b) != "a-key" {
		t.Errorf("file contents = %q, %v", b, err)
	}
	fi, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	// Group read is what makes the file readable at all: the AppArmor profile
	// denies wg dac_override, so root reads by the ordinary bits, and the
	// installer's setgid-root directory puts the file in group root.
	if fi.Mode().Perm() != 0o640 {
		t.Errorf("mode = %o, want 640 — owner and group root only", fi.Mode().Perm())
	}
}

// The upgrade path: a host installed before this change has no PskDir and its
// service user cannot create one under /etc. Falling back keeps it working —
// it is not on 26.04, or the old location would not have worked there either.
func TestWritePskFallsBackWhenPskDirIsMissing(t *testing.T) {
	runtime := t.TempDir()
	h := &Handler{cfg: Config{
		PskDir:     filepath.Join(t.TempDir(), "created-by-no-installer"),
		RuntimeDir: runtime,
	}}

	path, err := h.writePsk("a-key")
	if err != nil {
		t.Fatalf("writePsk should fall back, got: %v", err)
	}
	defer os.Remove(path)

	if got := filepath.Dir(path); got != runtime {
		t.Errorf("fell back to %s, want the runtime dir %s", got, runtime)
	}
	// The fallback is the pre-1.2.0 behaviour, owner-only included.
	if fi, err := os.Stat(path); err != nil || fi.Mode().Perm() != 0o600 {
		t.Errorf("fallback file mode = %v (%v), want 600", fi.Mode().Perm(), err)
	}
}

func TestPskDirDefaultsUnderEtcWireguard(t *testing.T) {
	t.Setenv("ISLANDR_PSK_DIR", "")
	if got := pskDir(); got != "/etc/wireguard/islandr" {
		t.Errorf("pskDir() = %s, want /etc/wireguard/islandr", got)
	}
}

func TestPskDirCanBeOverridden(t *testing.T) {
	t.Setenv("ISLANDR_PSK_DIR", "/somewhere/else")
	if got := pskDir(); got != "/somewhere/else" {
		t.Errorf("pskDir() = %s, want the override", got)
	}
}

// PskDir is on disk under /etc, not a tmpfs like /run/islandr: a proxy killed
// between writing a key and removing it would leave that key there for good,
// and /etc is what config backups and etckeeper capture. A key lives for one
// wg call, so anything older than a minute is a leftover, never one in use.
func TestSweepStalePskRemovesLeftoversOnly(t *testing.T) {
	dir := t.TempDir()
	old := time.Now().Add(-2 * time.Minute)

	leftover := filepath.Join(dir, "psk-123")
	inUse := filepath.Join(dir, "psk-456")
	notOurs := filepath.Join(dir, "operator-notes.txt")
	for _, p := range []string{leftover, inUse, notOurs} {
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for _, p := range []string{leftover, notOurs} {
		if err := os.Chtimes(p, old, old); err != nil {
			t.Fatal(err)
		}
	}

	sweepStalePsk(dir, time.Minute)

	if _, err := os.Stat(leftover); !os.IsNotExist(err) {
		t.Errorf("leftover key %s survived the sweep", leftover)
	}
	if _, err := os.Stat(inUse); err != nil {
		t.Errorf("a key still in use was removed: %v", err)
	}
	if _, err := os.Stat(notOurs); err != nil {
		t.Errorf("a file that is not a key was removed: %v", err)
	}
}

func TestSweepStalePskOfAMissingDirIsANoOp(t *testing.T) {
	sweepStalePsk(filepath.Join(t.TempDir(), "missing"), time.Minute)
	sweepStalePsk("", time.Minute)
}
