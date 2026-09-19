package de.chriscohnen.islandr.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A challenge the server does not remember is not a challenge. These tests pin
 * the two properties that make it one: single use, and scoped to the ceremony
 * it was issued for.
 */
class WebAuthnChallengesTest {

    private static final String SUBJECT = WebAuthnCredential.LOCAL_ADMIN;
    private static final String RP = "hub.islandr.internal";

    @Test
    void aChallengeComesBackOnce() {
        WebAuthnChallenges c = new WebAuthnChallenges();
        c.put(SUBJECT, RP, "abc");

        assertThat(c.consume(SUBJECT, RP)).isEqualTo("abc");
        assertThat(c.consume(SUBJECT, RP))
                .as("a second use is a replay, and there is nothing left to replay against")
                .isNull();
    }

    @Test
    void startingANewCeremonyDiscardsThePreviousChallenge() {
        WebAuthnChallenges c = new WebAuthnChallenges();
        c.put(SUBJECT, RP, "first");
        c.put(SUBJECT, RP, "second");

        assertThat(c.consume(SUBJECT, RP)).isEqualTo("second");
    }

    @Test
    void aChallengeIssuedForAnotherNameIsNotReturnedHere() {
        WebAuthnChallenges c = new WebAuthnChallenges();
        c.put(SUBJECT, "konsole.firma.de", "elsewhere");

        assertThat(c.consume(SUBJECT, RP)).isNull();
    }

    @Test
    void nothingPendingIsNull() {
        assertThat(new WebAuthnChallenges().consume(SUBJECT, RP)).isNull();
    }
}
