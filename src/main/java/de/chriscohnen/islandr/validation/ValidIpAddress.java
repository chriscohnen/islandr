package de.chriscohnen.islandr.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Accepts any valid IPv4 or IPv6 address string, or null/blank (use {@code @NotBlank} alongside
 * when the field is required). Delegates to {@link java.net.InetAddress#getByName}.
 */
@Documented
@Constraint(validatedBy = IpAddressValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidIpAddress {
    String message() default "must be a valid IPv4 or IPv6 address (e.g. 10.8.0.5 or fd11::3)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
