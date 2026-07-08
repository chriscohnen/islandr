package main

import (
	"encoding/json"
	"testing"
)

// recordingExec records every command it is asked to run and returns canned
// output. Tests use it to assert both what gets executed and — crucially — that
// rejected requests never reach the executor at all.
type recordingExec struct {
	calls [][]string
	stdin [][]byte
	out   string
	err   error
}

func (e *recordingExec) Run(name string, args []string, stdin []byte) (string, error) {
	e.calls = append(e.calls, append([]string{name}, args...))
	e.stdin = append(e.stdin, stdin)
	return e.out, e.err
}

func testConfig() Config {
	return Config{Iface: "wg0", RulesetPath: "/var/lib/islandr/ruleset.nft", RuntimeDir: ""}
}

// A syntactically valid WireGuard key: base64 of 32 bytes (ends in a single "=").
const validKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

func decodeResp(t *testing.T, line []byte) Response {
	t.Helper()
	var r Response
	if err := json.Unmarshal(line, &r); err != nil {
		t.Fatalf("response is not valid JSON: %q (%v)", line, err)
	}
	return r
}

func TestUnknownOpRejectedWithoutExec(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{"op":"rm_rf"}`)))
	if resp.Ok {
		t.Fatalf("unknown op must not be ok")
	}
	if len(ex.calls) != 0 {
		t.Fatalf("unknown op must not execute anything, ran: %v", ex.calls)
	}
}

func TestMalformedJsonRejectedWithoutExec(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{not json`)))
	if resp.Ok {
		t.Fatalf("malformed json must not be ok")
	}
	if len(ex.calls) != 0 {
		t.Fatalf("malformed json must not execute anything")
	}
}

// A valid op must actually dispatch to the executor — this stops a "always
// return ok:false" stub from passing the two rejection tests above.
func TestValidRemovePeerExecutes(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{"op":"wg_remove_peer","pubkey":"`+validKey+`"}`)))
	if !resp.Ok {
		t.Fatalf("valid wg_remove_peer should succeed, got error: %q", resp.Error)
	}
	if len(ex.calls) != 1 {
		t.Fatalf("expected exactly one exec, got: %v", ex.calls)
	}
	want := []string{"wg", "set", "wg0", "peer", validKey, "remove"}
	if !equalArgs(ex.calls[0], want) {
		t.Fatalf("wrong command\n got: %v\nwant: %v", ex.calls[0], want)
	}
}

func equalArgs(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
