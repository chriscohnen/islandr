package de.chriscohnen.islandr.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class IpAddressValidator implements ConstraintValidator<ValidIpAddress, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null || value.isBlank()) return true; // null/blank allowed; use @NotBlank to require
        try {
            InetAddress.getByName(value.trim());
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
