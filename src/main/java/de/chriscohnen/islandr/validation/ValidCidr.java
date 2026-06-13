package de.chriscohnen.islandr.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Accepts any valid IPv4 or IPv6 CIDR string, or null/blank.
 * Delegates to {@link de.chriscohnen.islandr.peer.IpSubnet#parse}.
 */
@Documented
@Constraint(validatedBy = CidrValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCidr {
    String message() default "must be a valid IPv4 or IPv6 CIDR (e.g. 10.8.0.0/24 or fd11::/64)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
