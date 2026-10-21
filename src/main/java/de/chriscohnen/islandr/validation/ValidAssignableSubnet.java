package de.chriscohnen.islandr.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects a syntactically valid CIDR that can never hand out a host address
 * — IPv4 /31, /32; IPv6 /127, /128 (settings-reject-unusable-wg-subnet,
 * loop/TASK.md). Deliberately a separate constraint from {@link ValidCidr}
 * rather than folding this into it: {@code @ValidCidr} also guards the
 * split-tunnel supernet and site CIDRs, where a /32 is a perfectly
 * legitimate single host, not a mistake. Apply both annotations together
 * only on fields that must actually assign peer addresses — today just
 * {@code wgSubnet}/{@code wgSubnet6} on {@code SettingsDto.UpdateRequest}.
 *
 * <p>Null/blank passes (same convention as {@link ValidCidr}) — required-
 * ness is a separate concern, usually {@code @NotBlank}. An unparseable
 * CIDR also passes here; that failure belongs to {@code @ValidCidr}, so
 * this validator doesn't duplicate it and risk a different error message
 * for the same bad input.
 */
@Documented
@Constraint(validatedBy = AssignableSubnetValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAssignableSubnet {
    String message() default "subnet too narrow to assign any host address (needs at least /30 for IPv4 or /126 for IPv6)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
