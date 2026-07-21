package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
)

// Config holds the server-side constants. The interface name and ruleset path
// are fixed here, never taken from a request — that is the whole point of the
// allowlist: a caller cannot redirect a command at a different interface or file.
type Config struct {
	Iface       string
	RulesetPath string
	RuntimeDir  string // where short-lived preshared-key files are written (0600)
}

// Executor runs one host command as an argument vector (never a shell string).
// stdin, when non-nil, is fed to the process's standard input.
type Executor interface {
	Run(name string, args []string, stdin []byte) (string, error)
}

// Request is the line-delimited JSON the JVM ProxyClient sends. Field names match
// its Jackson output exactly.
//
// PresharedKey is a pointer, not a plain string: the field being *absent* (nil —
// "leave the peer's preshared key as-is") must be distinguishable from it being
// *present but empty* (pointer to "" — "clear the existing preshared key"). A
// plain string collapses both to the zero value and can never signal a clear.
type Request struct {
	Op           string  `json:"op"`
	Pubkey       string  `json:"pubkey"`
	AllowedIps   string  `json:"allowedIps"`
	PresharedKey *string `json:"presharedKey"`
}

// Response is the single JSON line sent back. Ok mirrors what ProxyClient reads;
// Error carries the reason on failure; Dump carries wg_show output.
type Response struct {
	Ok    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	Dump  string `json:"dump,omitempty"`
}

// Handler dispatches one request to the allowlisted command for its op.
type Handler struct {
	exec Executor
	cfg  Config
}

func NewHandler(exec Executor, cfg Config) *Handler {
	return &Handler{exec: exec, cfg: cfg}
}

// Handle parses one request line and returns one response line (no trailing
// newline; the caller frames it). Any parse/validation failure yields
// {"ok":false,"error":...} and never touches the executor.
func (h *Handler) Handle(line []byte) []byte {
	var req Request
	if err := json.Unmarshal(line, &req); err != nil {
		return marshal(fail("malformed request"))
	}
	return marshal(h.dispatch(req))
}

func (h *Handler) dispatch(req Request) Response {
	switch req.Op {
	case "wg_set_peer":
		return h.wgSetPeer(req)
	case "wg_remove_peer":
		if !validKeyMaterial(req.Pubkey) {
			return fail("invalid pubkey")
		}
		if _, err := h.exec.Run("wg", []string{"set", h.cfg.Iface, "peer", req.Pubkey, "remove"}, nil); err != nil {
			return fail("wg_remove_peer failed: " + err.Error())
		}
		return ok()
	case "wg_show":
		out, err := h.exec.Run("wg", []string{"show", h.cfg.Iface, "dump"}, nil)
		if err != nil {
			return fail("wg_show failed: " + err.Error())
		}
		return Response{Ok: true, Dump: out}
	case "nft_validate":
		if _, err := h.exec.Run("nft", []string{"-c", "-f", h.cfg.RulesetPath}, nil); err != nil {
			return fail("nft_validate failed: " + err.Error())
		}
		return ok()
	case "nft_reload":
		if _, err := h.exec.Run("nft", []string{"-f", h.cfg.RulesetPath}, nil); err != nil {
			return fail("nft_reload failed: " + err.Error())
		}
		return ok()
	default:
		return fail("unknown op: " + req.Op)
	}
}

func (h *Handler) wgSetPeer(req Request) Response {
	if !validKeyMaterial(req.Pubkey) {
		return fail("invalid pubkey")
	}
	allowed, err := validateAllowedIps(req.AllowedIps)
	if err != nil {
		return fail("invalid allowedIps: " + err.Error())
	}
	args := []string{"set", h.cfg.Iface, "peer", req.Pubkey, "allowed-ips", allowed}

	if req.PresharedKey != nil {
		psk := *req.PresharedKey
		// Non-empty must be valid key material; empty is a deliberate clear
		// (an empty file is equivalent to /dev/null for `wg set ... preshared-key`).
		if psk != "" && !validKeyMaterial(psk) {
			return fail("invalid presharedKey")
		}
		// wg never takes the PSK on the command line — it reads it from a file.
		// Write it to a short-lived 0600 file and remove it right after the call.
		pskPath, err := h.writePsk(psk)
		if err != nil {
			return fail("presharedKey write failed: " + err.Error())
		}
		defer os.Remove(pskPath)
		args = append(args, "preshared-key", pskPath)
	}

	if _, err := h.exec.Run("wg", args, nil); err != nil {
		return fail("wg_set_peer failed: " + err.Error())
	}
	return ok()
}

// writePsk writes the base64 key to a fresh 0600 file in the runtime dir and
// returns its path. os.CreateTemp already creates the file with mode 0600.
func (h *Handler) writePsk(key string) (string, error) {
	dir := h.cfg.RuntimeDir
	if dir == "" {
		dir = os.TempDir()
	}
	f, err := os.CreateTemp(dir, "psk-*")
	if err != nil {
		return "", err
	}
	path := f.Name()
	if _, err := f.WriteString(key); err != nil {
		f.Close()
		os.Remove(path)
		return "", err
	}
	if err := f.Close(); err != nil {
		os.Remove(path)
		return "", err
	}
	return path, nil
}

// validKeyMaterial accepts exactly a base64-encoded 32-byte value — the shape of
// a WireGuard public or preshared key. Anything else (wrong length, non-base64
// bytes, shell metacharacters, whitespace) fails to decode or fails the length
// check and is rejected, so it can never reach an argument vector.
func validKeyMaterial(s string) bool {
	raw, err := base64.StdEncoding.DecodeString(s)
	return err == nil && len(raw) == 32
}

// validateAllowedIps splits the comma-separated list and requires every element
// to be a syntactically valid CIDR. It returns the validated list unchanged so
// the exact addresses reach wg; anything non-CIDR (spaces, shell metacharacters,
// bare commands) fails net.ParseCIDR and the whole request is rejected.
func validateAllowedIps(s string) (string, error) {
	if s == "" {
		return "", errors.New("empty")
	}
	parts := strings.Split(s, ",")
	for _, p := range parts {
		if _, _, err := net.ParseCIDR(p); err != nil {
			return "", fmt.Errorf("not a CIDR: %q", p)
		}
	}
	return strings.Join(parts, ","), nil
}

func ok() Response           { return Response{Ok: true} }
func fail(m string) Response { return Response{Ok: false, Error: m} }

func marshal(r Response) []byte {
	b, err := json.Marshal(r)
	if err != nil {
		// Response is a fixed struct of strings/bool; marshaling cannot fail.
		return []byte(`{"ok":false,"error":"internal marshal error"}`)
	}
	return b
}
