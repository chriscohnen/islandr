package de.chriscohnen.islandr.wg;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link WgAdapter} backed by the {@code wg} CLI.
 *
 * <p>Notes for callers:
 * <ul>
 *   <li>{@link #genKeypair()} runs anywhere {@code wg} is installed (Linux, macOS via brew).
 *   <li>{@link #setPeer}/{@link #removePeer}/{@link #showPeers} need a live kernel interface
 *       — Linux production target only. Calling these on macOS without a userspace tunnel
 *       up will throw; that is expected and why dev defaults to {@code mock} mode.
 * </ul>
 *
 * <p>Process invocations are bounded to a 5-second wall clock per call. That's a generous
 * cap for what should be sub-100ms operations; if {@code wg} hangs longer something is
 * structurally wrong and we'd rather surface it than block the request thread.
 */
public class RealWgAdapter implements WgAdapter {

    private static final Logger LOG = Logger.getLogger(RealWgAdapter.class);
    private static final int CALL_TIMEOUT_SECONDS = 5;

    private final boolean useSudo;

    public RealWgAdapter(boolean useSudo) {
        this.useSudo = useSudo;
    }

    @Override
    public Keypair genKeypair() {
        // Key generation does not touch the kernel interface — never sudo.
        String privateKey = runCapture(new String[]{"wg", "genkey"}, null, false).trim();
        return new Keypair(privateKey, derivePublicKey(privateKey));
    }

    @Override
    public String derivePublicKey(String privateKey) {
        // Pure userspace crypto — never sudo.
        return runCapture(new String[]{"wg", "pubkey"}, privateKey, false).trim();
    }

    @Override
    public void setPeer(String iface, String publicKey, String allowedIps) {
        runCapture(new String[]{
                "wg", "set", iface,
                "peer", publicKey,
                "allowed-ips", allowedIps
        }, null, useSudo);
    }

    @Override
    public void removePeer(String iface, String publicKey) {
        runCapture(new String[]{
                "wg", "set", iface,
                "peer", publicKey,
                "remove"
        }, null, useSudo);
    }

    @Override
    public List<PeerStatus> showPeers(String iface) {
        String output = runCapture(new String[]{"wg", "show", iface, "dump"}, null, useSudo);
        return parseShowDump(output);
    }

    @Override
    public ServerInfo probeServer(String iface) {
        try {
            String output = runCapture(new String[]{"wg", "show", iface, "dump"}, null, useSudo);
            return parseServerInfo(output);
        } catch (WgException e) {
            LOG.infof("wg probe failed for iface %s: %s", iface, e.getMessage());
            return null;
        }
    }

    static ServerInfo parseServerInfo(String dumpOutput) {
        if (dumpOutput == null || dumpOutput.isBlank()) return null;
        String firstLine = dumpOutput.split("\n")[0].trim();
        String[] fields = firstLine.split("\t");
        // format: private-key  public-key  listen-port  fwmark
        if (fields.length < 3) return null;
        String publicKey = fields[1];
        int listenPort;
        try {
            listenPort = Integer.parseInt(fields[2]);
        } catch (NumberFormatException e) {
            listenPort = 51820;
        }
        return new ServerInfo(publicKey, listenPort);
    }

    /**
     * Parse the tab-separated output of {@code wg show <iface> dump}.
     *
     * <p>First line is the interface itself (private key, public key, listen port, fwmark).
     * Each subsequent line is one peer:
     * <pre>
     *   public-key  preshared-key  endpoint  allowed-ips  latest-handshake  rx  tx  persistent-keepalive
     * </pre>
     * "(none)" appears in place of missing values. Visible for tests.
     */
    static List<PeerStatus> parseShowDump(String dumpOutput) {
        List<PeerStatus> peers = new ArrayList<>();
        String[] lines = dumpOutput.split("\n");
        // skip line 0 (interface header)
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t");
            if (fields.length < 7) continue;

            String pubKey = fields[0];
            String endpoint = "(none)".equals(fields[2]) ? null : fields[2];
            String allowedIps = fields[3];
            long handshakeEpoch = parseLong(fields[4]);
            Instant lastHandshake = handshakeEpoch > 0 ? Instant.ofEpochSecond(handshakeEpoch) : null;
            long rx = parseLong(fields[5]);
            long tx = parseLong(fields[6]);

            peers.add(new PeerStatus(pubKey, endpoint, allowedIps, lastHandshake, rx, tx));
        }
        return peers;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Run a command, optionally piping {@code stdin}, and return stdout as a string.
     * Throws {@link WgException} on non-zero exit or timeout.
     *
     * <p>If {@code sudo} is true, the argv is prefixed with {@code "sudo"}. The caller
     * decides per-invocation because not every {@code wg} subcommand needs privilege
     * ({@code genkey}/{@code pubkey} are pure userspace).
     */
    private static String runCapture(String[] command, String stdin, boolean sudo) {
        String[] effective;
        if (sudo) {
            effective = new String[command.length + 1];
            effective[0] = "sudo";
            System.arraycopy(command, 0, effective, 1, command.length);
        } else {
            effective = command;
        }
        ProcessBuilder pb = new ProcessBuilder(effective).redirectErrorStream(false);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new WgException("failed to start " + command[0] + ": " + e.getMessage(), e);
        }

        if (stdin != null) {
            try (var out = proc.getOutputStream()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new WgException("failed to write stdin to " + command[0], e);
            }
        }

        String stdout = readStream(proc.getInputStream());
        String stderr = readStream(proc.getErrorStream());

        boolean finished;
        try {
            finished = proc.waitFor(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new WgException(command[0] + " interrupted", e);
        }
        if (!finished) {
            proc.destroyForcibly();
            throw new WgException(command[0] + " timed out after " + CALL_TIMEOUT_SECONDS + "s");
        }
        if (proc.exitValue() != 0) {
            LOG.warnf("%s exited with %d. stderr: %s", command[0], proc.exitValue(), stderr);
            throw new WgException(command[0] + " exited " + proc.exitValue() + ": " + stderr.trim());
        }
        return stdout;
    }

    private static String readStream(java.io.InputStream is) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            throw new WgException("failed to read process stream", e);
        }
        return sb.toString();
    }
}
