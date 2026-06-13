package de.chriscohnen.islandr.validation;

import de.chriscohnen.islandr.peer.IpSubnet;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CidrValidator implements ConstraintValidator<ValidCidr, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null || value.isBlank()) return true;
        try {
            IpSubnet.parse(value.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
