package de.chriscohnen.islandr.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A challenge the server does not remember is not a challenge. These tests pin
 * the two properties that make it one: single use, and scoped to the ceremony
 * it was issued for.
 */
class WebAuthnChallengesTest {

    /** The ceremony is part of the key; these cases are about TTL and single
     *  use, so they pick one and stay in it. That the two ceremonies cannot
     *  reach each other's slot is asserted in {@code WebAuthnCeremonyConfusionTest}. */
    private static final boolean LOGIN = false;

    private static final String SUBJECT = WebAuthnCredential.LOCAL_ADMIN;
    private static final String RP = "hub.islandr.internal";

    @Test
    void aChallengeComesBackOnce() {
        WebAuthnChallenges c = new WebAuthnChallenges();
        c.put(SUBJECT, RP, LOGIN, "abc");

        assertThat(c.consume(SUBJECT, RP, LOGIN)).isEqualTo("abc");
        assertThat(c.consume(SUBJECT, RP, LOGIN))
                .as("a second use is a replay, and there is nothing left to replay against")
                .isNull();
    }

    @Test
    void startingANewCeremonyDiscardsThePreviousChallenge() {
        WebAuthnChallenges c = new WebAuthnChallenges();
        c.put(SUBJECT, RP, LOGIN, "first");
        c.put(SUBJECT, RP, LOGIN, "second");

        assertThat(c.consume(SUBJECT, RP, LOGIN)).isEqualTo("second");
    }

    @Test
    void aChallengeIssuedForAnotherNameIsNotReturnedHere() {
        WebAuthnChallenges c = new WebAuthnChallenges();
        c.put(SUBJECT, "konsole.firma.de", LOGIN, "elsewhere");

        assertThat(c.consume(SUBJECT, RP, LOGIN)).isNull();
    }

    @Test
    void nothingPendingIsNull() {
        assertThat(new WebAuthnChallenges().consume(SUBJECT, RP, LOGIN)).isNull();
    }
}
