package de.chriscohnen.islandr.network;

import java.util.List;
import java.util.zip.CRC32;

/**
 * In-memory {@link NetworkDiagnosticsAdapter} for dev/tests (ADR-0025), mirroring
 * {@code MockWgAdapter}. Every tool reports available; latency is a deterministic
 * function of the target IP (same IP → same numbers), not random, so tests and
 * screenshots stay stable.
 */
public class MockNetworkDiagnosticsAdapter implements NetworkDiagnosticsAdapter {

    /** Test seam: makes every probe report the target as unreachable, without touching availability. */
    public volatile boolean forceUnreachable;

    @Override
    public Availability checkAvailability() {
        return new Availability(true, true, true);
    }

    @Override
    public PingResult ping(String ip, int count) {
        if (forceUnreachable) {
            return new PingResult(false, count, 0, 100.0, null, null, null, null,
                    "mock: " + ip + " unreachable");
        }
        double base = 1 + (hash(ip) % 40); // 1..40ms, deterministic per IP
        double avg = base + 0.5;
        return new PingResult(true, count, count, 0.0,
                base, avg, base + 1.2, 0.3, "mock: " + count + " probes to " + ip + ", avg " + avg + "ms");
    }

    @Override
    public TracepathResult tracepath(String ip) {
        if (forceUnreachable) {
            return new TracepathResult(List.of(new TracepathHop(1, null, null)), "mock: " + ip + " no reply");
        }
        double base = 1 + (hash(ip) % 40);
        return new TracepathResult(List.of(
                new TracepathHop(1, "10.0.0.1", round1(base * 0.3)),
                new TracepathHop(2, ip, base)
        ), "mock: path to " + ip);
    }

    @Override
    public MtrResult mtr(String ip, int cycles) {
        if (forceUnreachable) {
            return new MtrResult(List.of(new MtrHop(1, null, 100.0, cycles, null, null, null, null)),
                    "mock: " + ip + " no reply");
        }
        double base = 1 + (hash(ip) % 40);
        return new MtrResult(List.of(
                new MtrHop(1, "10.0.0.1", 0.0, cycles, round1(base * 0.3), round1(base * 0.3), round1(base * 0.25), round1(base * 0.4)),
                new MtrHop(2, ip, 0.0, cycles, base, base, base - 1, base + 1.5)
        ), "mock: mtr report to " + ip);
    }

    private static long hash(String s) {
        CRC32 crc = new CRC32();
        crc.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /** Real ping/tracepath/mtr output is always a handful of decimal digits (parsed straight
     *  from the tool's own text); the mock's fractional arithmetic (e.g. {@code base * 0.3})
     *  otherwise produces binary-float noise like {@code 6.8999999999999995} that a real probe
     *  would never show — round it away so mock output stays visually indistinguishable. */
    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
