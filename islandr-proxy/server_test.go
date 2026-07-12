package main

import (
	"bufio"
	"net"
	"strings"
	"testing"
	"time"
)

// serveConn must read exactly one request line, answer with one newline-framed
// JSON line, and close — matching ProxyClient's one-request-per-connection model.
func TestServeConnAnswersOneRequest(t *testing.T) {
	client, server := net.Pipe()
	h := NewHandler(&recordingExec{}, testConfig())

	go serveConn(server, h)
	go func() {
		client.SetWriteDeadline(time.Now().Add(2 * time.Second))
		client.Write([]byte(`{"op":"wg_remove_peer","pubkey":"` + validKey + `"}` + "\n"))
	}()

	client.SetReadDeadline(time.Now().Add(2 * time.Second))
	line, err := bufio.NewReader(client).ReadString('\n')
	if err != nil {
		t.Fatalf("reading response: %v", err)
	}
	if !strings.HasSuffix(line, "\n") {
		t.Fatalf("response must be newline-framed, got %q", line)
	}
	resp := decodeResp(t, []byte(strings.TrimSpace(line)))
	if !resp.Ok {
		t.Fatalf("expected ok, got error %q", resp.Error)
	}
	client.Close()
}

func TestServeConnRejectsUnknownOpButStillAnswers(t *testing.T) {
	client, server := net.Pipe()
	h := NewHandler(&recordingExec{}, testConfig())

	go serveConn(server, h)
	go func() {
		client.SetWriteDeadline(time.Now().Add(2 * time.Second))
		client.Write([]byte(`{"op":"nope"}` + "\n"))
	}()

	client.SetReadDeadline(time.Now().Add(2 * time.Second))
	line, err := bufio.NewReader(client).ReadString('\n')
	if err != nil {
		t.Fatalf("reading response: %v", err)
	}
	resp := decodeResp(t, []byte(strings.TrimSpace(line)))
	if resp.Ok {
		t.Fatalf("unknown op must answer ok:false")
	}
	client.Close()
}

func TestRuntimeDirPrefersSystemdEnv(t *testing.T) {
	t.Setenv("RUNTIME_DIRECTORY", "/run/custom")
	if got := runtimeDir(); got != "/run/custom" {
		t.Fatalf("runtimeDir() = %q, want /run/custom", got)
	}
}

func TestRuntimeDirFallsBackToRunIslandr(t *testing.T) {
	t.Setenv("RUNTIME_DIRECTORY", "")
	if got := runtimeDir(); got != "/run/islandr" {
		t.Fatalf("runtimeDir() = %q, want /run/islandr", got)
	}
}
