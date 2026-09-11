package de.chriscohnen.islandr.firewall;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases of the comment cap. nft rejects the entire ruleset over one
 * over-long comment, so this bound is the difference between a cosmetic loss
 * and a firewall that stops accepting any update at all.
 */
class RuleCommentLengthTest {

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void leavesACommentThatAlreadyFitsExactlyAsItIs() {
        String comment = "islandr:role=Admins peer=laptop user=Lena resource=nas tcp";

        assertThat(RuleBuilder.fitComment(comment)).isEqualTo(comment);
    }

    @Test
    void leavesACommentSittingExactlyOnTheLimitAlone() {
        // Off-by-one guard: the limit is inclusive, so a comment of exactly
        // COMMENT_MAX_BYTES must survive untouched rather than lose its last
        // character to a cap that fires one byte early.
        String comment = "x".repeat(RuleBuilder.COMMENT_MAX_BYTES);

        assertThat(RuleBuilder.fitComment(comment)).isEqualTo(comment);
    }

    @Test
    void trimsAnOverLongCommentToWithinTheLimit() {
        String comment = "islandr:reservation-required role=network peer=" + "a".repeat(200);

        String fitted = RuleBuilder.fitComment(comment);

        assertThat(bytes(fitted)).isLessThanOrEqualTo(RuleBuilder.COMMENT_MAX_BYTES);
        assertThat(fitted).endsWith("~");
        assertThat(fitted).startsWith("islandr:reservation-required role=network");
    }

    @Test
    void neverSplitsAMultiByteCharacter() {
        // A cut measured in bytes lands mid-character sooner or later, and half
        // a code point is an invalid string rather than a short one. German
        // names make this an everyday case, not a theoretical one.
        //
        // The padding is computed, not guessed: it places the two bytes of "ü"
        // astride the cut, so a byte-blind implementation has to mangle it.
        String prefix = "islandr:role=network peer=";
        String pad = "a".repeat(RuleBuilder.COMMENT_MAX_BYTES - 2 - prefix.length());
        String comment = prefix + pad + "ü" + "bergabe-server";
        assertThat(bytes(prefix + pad)).isEqualTo(RuleBuilder.COMMENT_MAX_BYTES - 2);

        String fitted = RuleBuilder.fitComment(comment);

        assertThat(bytes(fitted)).isLessThanOrEqualTo(RuleBuilder.COMMENT_MAX_BYTES);
        assertThat(fitted).doesNotContain("\uFFFD");          // no replacement character
        assertThat(fitted).isEqualTo(prefix + pad + "~");    // the straddling "ü" is dropped whole
    }
}
