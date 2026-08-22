package de.chriscohnen.islandr.network;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the static {@code parsePingOutput}/{@code parseTracepathOutput}/
 * {@code commandExists} parsers with canned iputils output — no process
 * execution, so this runs everywhere including CI hosts without {@code ping}.
 * Reflection is used only because the methods are package-private static
 * (deliberately, so {@link SocketNetworkDiagnosticsAdapter} can reuse them
 * without becoming public API).
 */
class RealNetworkDiagnosticsAdapterTest {

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = RealNetworkDiagnosticsAdapter.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void parsePingOutput_allRepliesReceived() throws Exception {
        String output = """
                PING 10.0.0.1 (10.0.0.1) 56(84) bytes of data.
                64 bytes from 10.0.0.1: icmp_seq=1 ttl=64 time=0.045 ms
                64 bytes from 10.0.0.1: icmp_seq=2 ttl=64 time=0.038 ms
                64 bytes from 10.0.0.1: icmp_seq=3 ttl=64 time=0.067 ms
                64 bytes from 10.0.0.1: icmp_seq=4 ttl=64 time=0.041 ms

                --- 10.0.0.1 ping statistics ---
                4 packets transmitted, 4 received, 0% packet loss, time 3054ms
                rtt min/avg/max/mdev = 0.038/0.047/0.067/0.012 ms
                """;
        NetworkDiagnosticsAdapter.PingResult r =
                (NetworkDiagnosticsAdapter.PingResult) invoke("parsePingOutput", new Class<?>[]{String.class, int.class}, output, 4);

        assertThat(r.reachable()).isTrue();
        assertThat(r.sent()).isEqualTo(4);
        assertThat(r.received()).isEqualTo(4);
        assertThat(r.lossPercent()).isEqualTo(0.0);
        assertThat(r.minMs()).isEqualTo(0.038);
        assertThat(r.avgMs()).isEqualTo(0.047);
        assertThat(r.maxMs()).isEqualTo(0.067);
        assertThat(r.mdevMs()).isEqualTo(0.012);
    }

    @Test
    void parsePingOutput_totalLoss_hasNullRttAndIsUnreachable() throws Exception {
        String output = """
                PING 10.0.0.99 (10.0.0.99) 56(84) bytes of data.

                --- 10.0.0.99 ping statistics ---
                4 packets transmitted, 0 received, 100% packet loss, time 3062ms
                """;
        NetworkDiagnosticsAdapter.PingResult r =
                (NetworkDiagnosticsAdapter.PingResult) invoke("parsePingOutput", new Class<?>[]{String.class, int.class}, output, 4);

        assertThat(r.reachable()).isFalse();
        assertThat(r.received()).isZero();
        assertThat(r.lossPercent()).isEqualTo(100.0);
        assertThat(r.minMs()).isNull();
        assertThat(r.avgMs()).isNull();
    }

    @Test
    void parseTracepathOutput_hopsWithReplies() throws Exception {
        String output = """
                 1?: [LOCALHOST]                      pmtu 1500
                 1:  10.0.0.1                                              0.234ms
                 1:  10.0.0.1                                              0.198ms
                 2:  10.0.0.2                                              1.532ms asymm  3
                     Resume: pmtu 1500 hops 2 back 2
                """;
        NetworkDiagnosticsAdapter.TracepathResult r =
                (NetworkDiagnosticsAdapter.TracepathResult) invoke("parseTracepathOutput", new Class<?>[]{String.class}, output);

        assertThat(r.hops()).hasSize(3);
        assertThat(r.hops().get(0).ttl()).isEqualTo(1);
        assertThat(r.hops().get(0).host()).isEqualTo("10.0.0.1");
        assertThat(r.hops().get(0).ms()).isEqualTo(0.234);
        assertThat(r.hops().get(2).ttl()).isEqualTo(2);
        assertThat(r.hops().get(2).host()).isEqualTo("10.0.0.2");
        assertThat(r.hops().get(2).ms()).isEqualTo(1.532);
    }

    @Test
    void parseTracepathOutput_noReplyHopKeepsTtlWithNullHostAndMs() throws Exception {
        String output = """
                 1:  no reply
                 2:  10.0.0.2                                              1.532ms
                """;
        NetworkDiagnosticsAdapter.TracepathResult r =
                (NetworkDiagnosticsAdapter.TracepathResult) invoke("parseTracepathOutput", new Class<?>[]{String.class}, output);

        assertThat(r.hops()).hasSize(2);
        assertThat(r.hops().get(0).ttl()).isEqualTo(1);
        assertThat(r.hops().get(0).host()).isNull();
        assertThat(r.hops().get(0).ms()).isNull();
    }

    @Test
    void commandExists_findsSomethingAlwaysOnPathInCi() throws Exception {
        // "sh" is present on every CI/dev host this project targets (Linux, macOS).
        assertThat((Boolean) invoke("commandExists", new Class<?>[]{String.class}, "sh")).isTrue();
        assertThat((Boolean) invoke("commandExists", new Class<?>[]{String.class}, "definitely-not-a-real-binary-xyz")).isFalse();
    }
}
