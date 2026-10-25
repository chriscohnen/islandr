package main

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// connDeadline bounds a single request/response so a stuck client cannot pin a
// goroutine forever. One request per connection, so this is generous.
const connDeadline = 10 * time.Second

// serve accepts connections and handles each on its own goroutine.
func serve(l net.Listener, h *Handler) error {
	for {
		conn, err := l.Accept()
		if err != nil {
			return err
		}
		go serveConn(conn, h)
	}
}

// serveConn reads exactly one newline-terminated request, writes one
// newline-framed response, and closes — matching ProxyClient's protocol.
func serveConn(conn net.Conn, h *Handler) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(connDeadline))

	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if len(line) == 0 && err != nil {
		return // client closed without sending anything
	}
	line = bytes.TrimRight(line, "\r\n")

	resp := h.Handle(line)
	_, _ = conn.Write(append(resp, '\n'))
}

// listenFromSystemd takes the listening socket from systemd socket activation
// (the .socket unit), so ownership and mode (0600, islandr:islandr) are set by
// systemd, not by us — that is how R-120 is met without custom code. Per the
// sd_listen_fds(3) protocol the first passed fd is number 3 (SD_LISTEN_FDS_START).
func listenFromSystemd() (net.Listener, error) {
	if pidStr := os.Getenv("LISTEN_PID"); pidStr != "" {
		if pid, err := strconv.Atoi(pidStr); err == nil && pid != os.Getpid() {
			return nil, fmt.Errorf("LISTEN_PID %d is not our pid %d", pid, os.Getpid())
		}
	}
	nfds, err := strconv.Atoi(os.Getenv("LISTEN_FDS"))
	if err != nil || nfds < 1 {
		return nil, errors.New("no systemd socket passed (LISTEN_FDS unset); run under islandr-proxy.socket")
	}
	const listenFdsStart = 3
	f := os.NewFile(uintptr(listenFdsStart), "islandr-proxy.socket")
	l, err := net.FileListener(f)
	if err != nil {
		return nil, fmt.Errorf("wrapping systemd socket fd: %w", err)
	}
	return l, nil
}

// runtimeDir is where short-lived preshared-key files are written. systemd sets
// RUNTIME_DIRECTORY when RuntimeDirectory= is used; otherwise fall back to the
// well-known /run/islandr that tmpfiles.d / install.sh create (islandr:islandr,
// mode 0700) — never a world-readable temp dir for key material.
func runtimeDir() string {
	if d := os.Getenv("RUNTIME_DIRECTORY"); d != "" {
		return d
	}
	return "/run/islandr"
}

// pskDir is where the short-lived preshared-key file is written. It must be a
// path the AppArmor profile for wg permits to read — on Ubuntu 26.04 that is
// only /etc/wireguard and below, whatever uid wg runs as. install-proxy.sh
// creates it 2750, owned by the service user and setgid root, so root's wg
// can read the 0640 key files through group root (see writePsk).
func pskDir() string {
	if d := os.Getenv("ISLANDR_PSK_DIR"); d != "" {
		return d
	}
	return "/etc/wireguard/islandr"
}

// sweepStalePsk removes preshared-key files a killed proxy left behind in dir.
// Called once at startup. The old location, /run/islandr, is a tmpfs and gone
// at the latest on reboot; /etc/wireguard/islandr is on disk, where config
// backups and etckeeper capture it, so a key left there by a crash would stay
// for good. A key lives for one wg call, so anything older than maxAge is a
// leftover. Only files named the way writePsk names them are touched.
func sweepStalePsk(dir string, maxAge time.Duration) {
	if dir == "" {
		return
	}
	matches, err := filepath.Glob(filepath.Join(dir, "psk-*"))
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-maxAge)
	for _, p := range matches {
		fi, err := os.Stat(p)
		if err != nil || !fi.Mode().IsRegular() || !fi.ModTime().Before(cutoff) {
			continue
		}
		if err := os.Remove(p); err != nil {
			log.Printf("could not remove leftover preshared-key file %s: %v", p, err)
			continue
		}
		log.Printf("removed a leftover preshared-key file %s", p)
	}
}

// osExec runs the allowlisted command, escalating through sudo only when asked
// (relying on the scoped sudoers rules of ADR-0011 — the islandr user may run
// exactly wg/nft as root). ping/tracepath pass sudo=false: they need no
// elevation on a modern Linux host (ADR-0025 §3), and islandr-proxy.sudoers
// does not grant them anyway. The command is always an argument vector — never
// a shell string — so validated values cannot be reinterpreted by a shell.
type osExec struct{}

// Run's stdout return is non-empty even on a non-zero exit: net_ping (ADR-0025)
// needs the captured report from `ping` exiting 1 on packet loss, which is a
// successful invocation, not a failure. wg/nft call-sites only ever check the
// error, so returning stdout alongside it changes nothing for them.
func (osExec) Run(name string, args []string, stdin []byte, sudo bool) (string, error) {
	argv := append([]string{name}, args...)
	var cmd *exec.Cmd
	if sudo {
		cmd = exec.Command("sudo", argv...)
	} else {
		cmd = exec.Command(argv[0], argv[1:]...)
	}
	if stdin != nil {
		cmd.Stdin = bytes.NewReader(stdin)
	}
	var out, errb bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &errb
	if err := cmd.Run(); err != nil {
		if msg := strings.TrimSpace(errb.String()); msg != "" {
			return out.String(), errors.New(msg)
		}
		return out.String(), err
	}
	return out.String(), nil
}
