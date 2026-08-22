package main

import (
	"encoding/json"
	"fmt"
	"testing"
)

// recordingExec records every command it is asked to run and returns canned
// output. Tests use it to assert both what gets executed and — crucially — that
// rejected requests never reach the executor at all.
type recordingExec struct {
	calls [][]string
	stdin [][]byte
	sudo  []bool
	out   string
	err   error
}

func (e *recordingExec) Run(name string, args []string, stdin []byte, sudo bool) (string, error) {
	e.calls = append(e.calls, append([]string{name}, args...))
	e.stdin = append(e.stdin, stdin)
	e.sudo = append(e.sudo, sudo)
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
	if !ex.sudo[0] {
		t.Fatalf("wg_remove_peer must escalate via sudo (ADR-0011)")
	}
}

// ADR-0025: net_ping/net_tracepath must reject a non-IP target before ever
// reaching the executor — the whole point of the allowlist is that a caller
// can never redirect the probe at an arbitrary hostname/shell token.
func TestNetPingRejectsNonIpTarget(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{"op":"net_ping","ip":"not-an-ip; rm -rf /"}`)))
	if resp.Ok {
		t.Fatalf("non-IP target must not be ok")
	}
	if len(ex.calls) != 0 {
		t.Fatalf("non-IP target must not execute anything, ran: %v", ex.calls)
	}
}

func TestNetPingExecutesWithClampedCount(t *testing.T) {
	ex := &recordingExec{out: "4 packets transmitted, 4 received, 0% packet loss"}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{"op":"net_ping","ip":"10.0.0.1","count":999}`)))
	if !resp.Ok {
		t.Fatalf("valid net_ping should succeed, got error: %q", resp.Error)
	}
	want := []string{"ping", "-c", "4", "-W", "2", "10.0.0.1"}
	if !equalArgs(ex.calls[0], want) {
		t.Fatalf("out-of-range count must be clamped\n got: %v\nwant: %v", ex.calls[0], want)
	}
	if resp.Dump != ex.out {
		t.Fatalf("expected raw ping stdout passed through, got: %q", resp.Dump)
	}
	// ADR-0025: ping needs no elevation on a modern Linux host (CAP_NET_RAW file
	// capability or net.ipv4.ping_group_range) — and islandr-proxy.sudoers does
	// not grant it anyway, so escalating here would just fail.
	if ex.sudo[0] {
		t.Fatalf("net_ping must not escalate via sudo")
	}
}

// A `ping` exit status of 1 (100% packet loss) is a normal, informative result —
// not a proxy-level failure — as long as it produced a report to parse.
func TestNetPingWithPacketLossIsStillOk(t *testing.T) {
	ex := &recordingExec{out: "4 packets transmitted, 0 received, 100% packet loss", err: fmt.Errorf("exit status 1")}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{"op":"net_ping","ip":"10.0.0.9"}`)))
	if !resp.Ok {
		t.Fatalf("100%% loss with a captured report must still be ok, got error: %q", resp.Error)
	}
	if resp.Dump != ex.out {
		t.Fatalf("expected the loss report passed through, got: %q", resp.Dump)
	}
}

// tracepath needs no elevation at all on Linux (UDP + PMTUD, ADR-0025 §3).
func TestNetTracepathDoesNotEscalate(t *testing.T) {
	ex := &recordingExec{out: " 1:  10.0.0.1  0.234ms\n"}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{"op":"net_tracepath","ip":"10.0.0.1"}`)))
	if !resp.Ok {
		t.Fatalf("valid net_tracepath should succeed, got error: %q", resp.Error)
	}
	if ex.sudo[0] {
		t.Fatalf("net_tracepath must not escalate via sudo")
	}
}

func TestNetTracepathRejectsNonIpTarget(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{"op":"net_tracepath","ip":"$(reboot)"}`)))
	if resp.Ok {
		t.Fatalf("non-IP target must not be ok")
	}
	if len(ex.calls) != 0 {
		t.Fatalf("non-IP target must not execute anything, ran: %v", ex.calls)
	}
}

func TestNetAvailabilityNeverExecutes(t *testing.T) {
	ex := &recordingExec{}
	h := NewHandler(ex, testConfig())
	resp := decodeResp(t, h.Handle([]byte(`{"op":"net_availability"}`)))
	if !resp.Ok {
		t.Fatalf("net_availability should always be ok, got error: %q", resp.Error)
	}
	if len(ex.calls) != 0 {
		t.Fatalf("net_availability must not shell out — it only stats $PATH, ran: %v", ex.calls)
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
