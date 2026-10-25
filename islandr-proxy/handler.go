package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"strings"
	"sync"
)

// Config holds the server-side constants. The interface name and ruleset path
// are fixed here, never taken from a request — that is the whole point of the
// allowlist: a caller cannot redirect a command at a different interface or file.
type Config struct {
	Iface       string
	RulesetPath string
	RuntimeDir  string // socket and other runtime state (0700 islandr:islandr)
	PskDir      string // where short-lived preshared-key files are written (0600)
}

// Executor runs one host command as an argument vector (never a shell string).
// stdin, when non-nil, is fed to the process's standard input. sudo selects
// whether the call escalates: wg/nft genuinely need root (ADR-0011); ping and
// tracepath do not on a modern Linux host — iputils ping normally carries a
// CAP_NET_RAW file capability (or the kernel's net.ipv4.ping_group_range sysctl
// permits an unprivileged ICMP socket outright), and tracepath's UDP+PMTUD
// approach needs no elevation at all (ADR-0025 §3). Escalating them anyway would
// also fail outright here: islandr-proxy.sudoers only grants wg/nft, not ping.
type Executor interface {
	Run(name string, args []string, stdin []byte, sudo bool) (string, error)
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
	Ip           string  `json:"ip"`    // net_ping / net_tracepath target — validated as a bare IP, never a shell token
	Count        int     `json:"count"` // net_ping sample count — re-clamped here, not trusted from the caller
}

// Response is the single JSON line sent back. Ok mirrors what ProxyClient reads;
// Error carries the reason on failure; Dump carries wg_show/ping/tracepath output.
// Ping/Tracepath/Mtr report tool availability for net_availability.
type Response struct {
	Ok        bool   `json:"ok"`
	Error     string `json:"error,omitempty"`
	Dump      string `json:"dump,omitempty"`
	Ping      bool   `json:"ping,omitempty"`
	Tracepath bool   `json:"tracepath,omitempty"`
	Mtr       bool   `json:"mtr,omitempty"`
}

// pingTimeoutSeconds bounds a single `ping` reply wait (matches the JVM real adapter).
const pingTimeoutSeconds = 2

// maxPingCount caps the sample size regardless of what the caller asked for
// (ADR-0025 R-183) — the JVM side already fixes this server-side, this is
// defense in depth against a proxy talked to directly.
const maxPingCount = 10

// Handler dispatches one request to the allowlisted command for its op.
type Handler struct {
	exec Executor
	cfg  Config

	// The fallback is harmless below Ubuntu 26.04 and happens for every
	// preshared-key peer — once per process is news, every time is noise.
	pskFallbackLogged sync.Once
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
		if _, err := h.exec.Run("wg", []string{"set", h.cfg.Iface, "peer", req.Pubkey, "remove"}, nil, true); err != nil {
			return fail("wg_remove_peer failed: " + err.Error())
		}
		return ok()
	case "wg_show":
		out, err := h.exec.Run("wg", []string{"show", h.cfg.Iface, "dump"}, nil, true)
		if err != nil {
			return fail("wg_show failed: " + err.Error())
		}
		return Response{Ok: true, Dump: out}
	case "nft_validate":
		if _, err := h.exec.Run("nft", []string{"-c", "-f", h.cfg.RulesetPath}, nil, true); err != nil {
			return fail("nft_validate failed: " + err.Error())
		}
		return ok()
	case "nft_reload":
		if _, err := h.exec.Run("nft", []string{"-f", h.cfg.RulesetPath}, nil, true); err != nil {
			return fail("nft_reload failed: " + err.Error())
		}
		return ok()
	case "net_availability":
		return Response{Ok: true,
			Ping:      commandExists("ping"),
			Tracepath: commandExists("tracepath"),
			Mtr:       commandExists("mtr")}
	case "net_ping":
		return h.netPing(req)
	case "net_tracepath":
		return h.netTracepath(req)
	case "net_mtr":
		return h.netMtr(req)
	default:
		return fail("unknown op: " + req.Op)
	}
}

// netPing runs `ping -c <count> -W 2 <ip>`. A lost reply (partial or 100% loss) is
// a normal, successful invocation — ping exits 1 on 100% loss, not an error — so
// exit 1 alongside captured stdout is still Ok:true; only "never actually probed"
// (bad args, DNS/socket error) is a failure. Parsing the report happens on the JVM
// side (RealNetworkDiagnosticsAdapter.parsePingOutput), so this stays a thin,
// auditable pass-through, same shape as wg_show handing back a raw dump.
func (h *Handler) netPing(req Request) Response {
	ip := net.ParseIP(req.Ip)
	if ip == nil {
		return fail("invalid ip")
	}
	count := req.Count
	if count <= 0 || count > maxPingCount {
		count = 4
	}
	out, err := h.exec.Run("ping", []string{"-c", fmt.Sprint(count), "-W", fmt.Sprint(pingTimeoutSeconds), ip.String()}, nil, false)
	if err != nil {
		// exec.Command surfaces a plain exit-status error for ping's exit 1 (loss) too;
		// distinguish by re-running is unnecessary — h.exec.Run already returns stdout on
		// non-zero exit via the shared osExec captured-output path (see server.go)  when
		// the command produced output at all, so an error here with no output at all means
		// ping never ran (bad args, target unresolvable, permission denied).
		if out == "" {
			return fail("net_ping failed: " + err.Error())
		}
	}
	return Response{Ok: true, Dump: out}
}

func (h *Handler) netTracepath(req Request) Response {
	ip := net.ParseIP(req.Ip)
	if ip == nil {
		return fail("invalid ip")
	}
	out, err := h.exec.Run("tracepath", []string{ip.String()}, nil, false)
	if err != nil && out == "" {
		return fail("net_tracepath failed: " + err.Error())
	}
	return Response{Ok: true, Dump: out}
}

// netMtr runs `mtr --report --report-cycles <count> -n <ip>` — never sudo, same
// reasoning as net_ping (ADR-0025 §3): mtr needs no more elevation than ping does
// on a modern host, and it's opportunistic-only besides (ADR-0025 §1). Reuses
// Count/maxPingCount rather than a separate field/cap — "how many probes per
// hop" is the same server-side-bounded concept net_ping already has.
func (h *Handler) netMtr(req Request) Response {
	ip := net.ParseIP(req.Ip)
	if ip == nil {
		return fail("invalid ip")
	}
	cycles := req.Count
	if cycles <= 0 || cycles > maxPingCount {
		cycles = 4
	}
	out, err := h.exec.Run("mtr", []string{"--report", "--report-cycles", fmt.Sprint(cycles), "-n", ip.String()}, nil, false)
	if err != nil {
		return fail("net_mtr failed: " + err.Error())
	}
	return Response{Ok: true, Dump: out}
}

// commandExists walks $PATH looking for an executable file — same dependency-free
// approach as the JVM side (RealNetworkDiagnosticsAdapter.commandExists), so the
// proxy never assumes `which` itself is present.
func commandExists(name string) bool {
	path := os.Getenv("PATH")
	if path == "" {
		path = "/usr/bin:/bin:/usr/sbin:/sbin"
	}
	for _, dir := range strings.Split(path, string(os.PathListSeparator)) {
		if dir == "" {
			continue
		}
		info, err := os.Stat(dir + "/" + name)
		if err == nil && !info.IsDir() && info.Mode()&0111 != 0 {
			return true
		}
	}
	return false
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

	if _, err := h.exec.Run("wg", args, nil, true); err != nil {
		return fail("wg_set_peer failed: " + err.Error())
	}
	return ok()
}

// writePsk writes the base64 key to a fresh file and returns its path.
//
// The directory is not a free choice. Ubuntu 26.04 ships an enforcing AppArmor
// profile for /usr/bin/wg whose only file rule is
// "file rw @{etc_rw}/wireguard/{,**}", and which denies the binary
// dac_override and dac_read_search — so a key under /run, /tmp or /root is
// refused even though wg runs as root, and wg reports nothing but
// "fopen: Permission denied". PskDir is created by the installer under
// /etc/wireguard; a host that predates it falls back to the runtime dir, which
// is what earlier releases used and still works on anything below 26.04.
func (h *Handler) writePsk(key string) (string, error) {
	f, err := os.CreateTemp(h.cfg.PskDir, "psk-*")
	inPskDir := err == nil && h.cfg.PskDir != ""
	if err != nil && h.cfg.PskDir != "" {
		h.pskFallbackLogged.Do(func() {
			log.Printf("preshared-key dir %s unusable (%v) — falling back to %s, which wg cannot read "+
				"where an AppArmor profile for wg exists (Ubuntu 26.04). Logged once.",
				h.cfg.PskDir, err, h.cfg.RuntimeDir)
		})
		dir := h.cfg.RuntimeDir
		if dir == "" {
			dir = os.TempDir()
		}
		f, err = os.CreateTemp(dir, "psk-*")
	}
	if err != nil {
		return "", err
	}
	path := f.Name()
	// In PskDir: 0640. The AppArmor profile denies wg dac_override, so root
	// reads by the ordinary bits like anyone else, and a 0600 file owned by
	// the service user is closed to it (measured: refused). The installer
	// makes the directory setgid root, so the file is born in group root and
	// group read lets root's wg in. The fallback keeps CreateTemp's 0600.
	if inPskDir {
		if err := f.Chmod(0o640); err != nil {
			f.Close()
			os.Remove(path)
			return "", err
		}
	}
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
