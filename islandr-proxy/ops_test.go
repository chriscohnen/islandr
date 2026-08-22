package main

import (
	"os"
	"testing"
)

func handleLine(t *testing.T, h *Handler, line string) Response {
	t.Helper()
	return decodeResp(t, h.Handle([]byte(line)))
}

func TestSetPeerBuildsCommand(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := handleLine(t, h, `{"op":"wg_set_peer","pubkey":"`+validKey+`","allowedIps":"10.0.0.2/32"}`)
	if !resp.Ok {
		t.Fatalf("valid wg_set_peer should succeed: %q", resp.Error)
	}
	want := []string{"wg", "set", "wg0", "peer", validKey, "allowed-ips", "10.0.0.2/32"}
	if len(ex.calls) != 1 || !equalArgs(ex.calls[0], want) {
		t.Fatalf("wrong command\n got: %v\nwant: %v", ex.calls, want)
	}
}

func TestSetPeerAcceptsMultipleCidrs(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := handleLine(t, h, `{"op":"wg_set_peer","pubkey":"`+validKey+`","allowedIps":"10.0.0.2/32,fd00::2/128"}`)
	if !resp.Ok {
		t.Fatalf("dual-stack allowed-ips should succeed: %q", resp.Error)
	}
	want := []string{"wg", "set", "wg0", "peer", validKey, "allowed-ips", "10.0.0.2/32,fd00::2/128"}
	if !equalArgs(ex.calls[0], want) {
		t.Fatalf("wrong command\n got: %v\nwant: %v", ex.calls[0], want)
	}
}

// Injection and malformed values in allowed-ips must be rejected before exec.
func TestSetPeerRejectsBadAllowedIps(t *testing.T) {
	cases := map[string]string{
		"shell metachars": "10.0.0.2/32; rm -rf /",
		"bare command":    "$(reboot)",
		"whitespace":      "10.0.0.2/32 10.0.0.3/32",
		"not a cidr":      "notacidr",
		"missing mask":    "10.0.0.2",
		"empty":           "",
	}
	for name, ips := range cases {
		t.Run(name, func(t *testing.T) {
			ex := &recordingExec{}
			h := NewHandler(ex, testConfig())
			resp := handleLine(t, h, `{"op":"wg_set_peer","pubkey":"`+validKey+`","allowedIps":"`+ips+`"}`)
			if resp.Ok {
				t.Fatalf("allowedIps %q must be rejected", ips)
			}
			if len(ex.calls) != 0 {
				t.Fatalf("rejected request must not exec, ran: %v", ex.calls)
			}
		})
	}
}

func TestSetPeerRejectsBadPubkey(t *testing.T) {
	for _, key := range []string{"tooshort", "not+base64+but+44+chars+long+aaaaaaaaaaaa=", "key with spaces", ""} {
		ex := &recordingExec{}
		h := NewHandler(ex, testConfig())
		resp := handleLine(t, h, `{"op":"wg_set_peer","pubkey":"`+key+`","allowedIps":"10.0.0.2/32"}`)
		if resp.Ok || len(ex.calls) != 0 {
			t.Fatalf("pubkey %q must be rejected without exec", key)
		}
	}
}

// A preshared key is written to a short-lived 0600 file and passed by path (wg
// never takes it on the command line); the file must be gone once Handle returns.
func TestSetPeerWithPskWritesTempFileAndCleansUp(t *testing.T) {
	dir := t.TempDir()
	cfg := testConfig()
	cfg.RuntimeDir = dir

	var pskPath, pskContent string
	var pskMode os.FileMode
	ex := &recordingExec{}
	// Read the preshared-key file *during* exec, while it still exists.
	exReader := execFunc(func(name string, args []string, stdin []byte, sudo bool) (string, error) {
		for i, a := range args {
			if a == "preshared-key" && i+1 < len(args) {
				pskPath = args[i+1]
				b, _ := os.ReadFile(pskPath)
				pskContent = string(b)
				if st, err := os.Stat(pskPath); err == nil {
					pskMode = st.Mode().Perm()
				}
			}
		}
		return ex.Run(name, args, stdin, sudo)
	})
	h := NewHandler(exReader, cfg)
	resp := handleLine(t, h, `{"op":"wg_set_peer","pubkey":"`+validKey+`","allowedIps":"10.0.0.2/32","presharedKey":"`+validKey+`"}`)
	if !resp.Ok {
		t.Fatalf("wg_set_peer with psk should succeed: %q", resp.Error)
	}
	if pskContent != validKey {
		t.Fatalf("psk file content = %q, want %q", pskContent, validKey)
	}
	if pskMode != 0o600 {
		t.Fatalf("psk file mode = %v, want 0600", pskMode)
	}
	if _, err := os.Stat(pskPath); !os.IsNotExist(err) {
		t.Fatalf("psk file must be removed after Handle, stat err = %v", err)
	}
}

func TestSetPeerRejectsBadPsk(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := handleLine(t, h, `{"op":"wg_set_peer","pubkey":"`+validKey+`","allowedIps":"10.0.0.2/32","presharedKey":"tooshort"}`)
	if resp.Ok || len(ex.calls) != 0 {
		t.Fatalf("invalid psk must be rejected without exec")
	}
}

// presharedKey:"" (present but empty) must clear an existing PSK by passing an
// empty file to `preshared-key` — distinct from the field being absent, which
// must leave the peer's PSK untouched (no preshared-key arg at all).
func TestSetPeerEmptyPskClearsExisting(t *testing.T) {
	dir := t.TempDir()
	cfg := testConfig()
	cfg.RuntimeDir = dir

	var pskPath, pskContent string
	sawContent := false
	ex := &recordingExec{}
	exReader := execFunc(func(name string, args []string, stdin []byte, sudo bool) (string, error) {
		for i, a := range args {
			if a == "preshared-key" && i+1 < len(args) {
				pskPath = args[i+1]
				b, _ := os.ReadFile(pskPath)
				pskContent = string(b)
				sawContent = true
			}
		}
		return ex.Run(name, args, stdin, sudo)
	})
	h := NewHandler(exReader, cfg)
	resp := handleLine(t, h, `{"op":"wg_set_peer","pubkey":"`+validKey+`","allowedIps":"10.0.0.2/32","presharedKey":""}`)
	if !resp.Ok {
		t.Fatalf("clearing psk should succeed: %q", resp.Error)
	}
	if !sawContent {
		t.Fatalf("expected a preshared-key arg to be passed to wg")
	}
	if pskContent != "" {
		t.Fatalf("psk file content = %q, want empty (clear)", pskContent)
	}
	if _, err := os.Stat(pskPath); !os.IsNotExist(err) {
		t.Fatalf("psk file must be removed after Handle, stat err = %v", err)
	}
}

// omitting presharedKey entirely (vs. sending "") must NOT touch the peer's
// existing PSK — this is what TestSetPeerBuildsCommand already exercises
// implicitly (no presharedKey field, no preshared-key arg in the resulting
// command); this test makes the "leave untouched" contract explicit.
func TestSetPeerOmittedPskLeavesExistingUntouched(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := handleLine(t, h, `{"op":"wg_set_peer","pubkey":"`+validKey+`","allowedIps":"10.0.0.2/32"}`)
	if !resp.Ok {
		t.Fatalf("wg_set_peer without psk should succeed: %q", resp.Error)
	}
	for _, a := range ex.calls[0] {
		if a == "preshared-key" {
			t.Fatalf("omitted presharedKey must not add a preshared-key arg, got: %v", ex.calls[0])
		}
	}
}

func TestWgShowReturnsDump(t *testing.T) {
	ex := &recordingExec{out: "peerline1\npeerline2\n"}
	h := NewHandler(ex, testConfig())
	resp := handleLine(t, h, `{"op":"wg_show"}`)
	if !resp.Ok {
		t.Fatalf("wg_show should succeed: %q", resp.Error)
	}
	if resp.Dump != "peerline1\npeerline2\n" {
		t.Fatalf("dump = %q", resp.Dump)
	}
	want := []string{"wg", "show", "wg0", "dump"}
	if !equalArgs(ex.calls[0], want) {
		t.Fatalf("wrong command: %v", ex.calls[0])
	}
}

func TestNftValidateAndReloadUseConstantPath(t *testing.T) {
	for op, sub := range map[string]string{"nft_validate": "-c", "nft_reload": "-f"} {
		ex := &recordingExec{}
		h := NewHandler(ex, testConfig())
		resp := handleLine(t, h, `{"op":"`+op+`"}`)
		if !resp.Ok {
			t.Fatalf("%s should succeed: %q", op, resp.Error)
		}
		var want []string
		if op == "nft_validate" {
			want = []string{"nft", "-c", "-f", "/var/lib/islandr/ruleset.nft"}
		} else {
			want = []string{"nft", "-f", "/var/lib/islandr/ruleset.nft"}
		}
		if !equalArgs(ex.calls[0], want) {
			t.Fatalf("%s (%s) wrong command\n got: %v\nwant: %v", op, sub, ex.calls[0], want)
		}
	}
}

func TestExecErrorSurfacedAsNotOk(t *testing.T) {
	ex := &recordingExec{err: errString("nft: syntax error")}
	h := NewHandler(ex, testConfig())
	resp := handleLine(t, h, `{"op":"nft_reload"}`)
	if resp.Ok {
		t.Fatalf("exec failure must yield ok:false")
	}
	if resp.Error == "" {
		t.Fatalf("failure must carry an error message")
	}
}

// --- small test helpers ---

type execFunc func(name string, args []string, stdin []byte, sudo bool) (string, error)

func (f execFunc) Run(name string, args []string, stdin []byte, sudo bool) (string, error) {
	return f(name, args, stdin, sudo)
}

type errString string

func (e errString) Error() string { return string(e) }
