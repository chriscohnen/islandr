package de.chriscohnen.islandr.peer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out an address no other test in the suite is using, for tests that
 * need a peer to exist but do not care what it is reachable at.
 *
 * <p>Eleven test classes used to pick one themselves, nine of them with
 * {@code Math.random()} over ranges that overlapped each other and addresses
 * other classes write out literally. {@code peers.assigned_ip} is unique, the
 * whole suite shares one JVM and one database, and a few dozen inserts into a
 * 200-address range collide often enough to redden a run — seen on 2026-10-22
 * (fixed in one class only, which was not enough) and again on the 1.2.0-rc.3
 * tag, where {@code MyPeerResourceEditTest}'s counted {@code 10.9.0.150} met
 * {@code MyPeerResourceDeletionTest}'s random draw from {@code 150..239}.
 *
 * <p>Two properties make that impossible rather than unlikely:
 *
 * <ul>
 *   <li><b>Counted, not drawn.</b> Successive calls cannot repeat, so there is
 *       no birthday collision left to tune a range against.</li>
 *   <li><b>Out of reach of every literal.</b> The suite's literal addresses sit
 *       in {@code 10.8.0.x} (the test subnet, asserted on in dozens of places),
 *       in {@code 10.9.0.x}, and in {@code 10.9.1.x}–{@code 10.9.3.x}.
 *       Allocation starts at {@code 10.9.4.1}, past all of them.</li>
 * </ul>
 *
 * <p>The space is 250×254 addresses; the suite uses a few dozen. A test that
 * needs one <i>specific</i> address still writes it out — those assert on the
 * value, and this allocator is for the ones that do not.
 */
final class TestPeerIps {

    private TestPeerIps() {}

    private static final AtomicInteger NEXT = new AtomicInteger();

    /** The first third octet not already carrying literal addresses. */
    private static final int FIRST_BLOCK = 4;

    /** @return e.g. {@code 10.9.4.1}, then {@code 10.9.4.2}, … */
    static String next() {
        int n = NEXT.getAndIncrement();
        int block = FIRST_BLOCK + n / 254;
        if (block > 253) {
            throw new IllegalStateException("test address space exhausted after " + n + " peers");
        }
        return "10.9." + block + "." + (1 + n % 254);
    }
}
