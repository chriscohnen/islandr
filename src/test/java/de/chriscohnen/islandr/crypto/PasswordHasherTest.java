package de.chriscohnen.islandr.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PasswordHasher} (design 2026-07-05, F-01a): PBKDF2 hashing
 * of local user passwords with a per-user salt, stored PHC-style. Pure JDK crypto,
 * no dependency.
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashThenVerify_roundTrips() {
        String phc = hasher.hash("s3cret-pw");
        assertThat(hasher.verify("s3cret-pw", phc)).isTrue();
    }

    @Test
    void verify_rejectsWrongPassword() {
        String phc = hasher.hash("s3cret-pw");
        assertThat(hasher.verify("wrong-pw", phc)).isFalse();
    }

    /** Per-user random salt → the same password hashes to different strings. */
    @Test
    void hash_isSalted_soRepeatedHashesDiffer() {
        assertThat(hasher.hash("same-pw")).isNotEqualTo(hasher.hash("same-pw"));
    }

    @Test
    void hash_usesPbkdf2PhcFormat() {
        assertThat(hasher.hash("x")).startsWith("pbkdf2$sha256$");
    }

    /** A malformed or missing PHC string rejects cleanly — never throws. */
    @Test
    void verify_rejectsMalformedPhc_withoutThrowing() {
        assertThat(hasher.verify("x", "not-a-valid-phc")).isFalse();
        assertThat(hasher.verify("x", "")).isFalse();
        assertThat(hasher.verify("x", null)).isFalse();
    }
}
