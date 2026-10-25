package de.chriscohnen.islandr.validation;

import de.chriscohnen.islandr.peer.IpSubnet;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AssignableSubnetValidator implements ConstraintValidator<ValidAssignableSubnet, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null || value.isBlank()) return true;
        try {
            return IpSubnet.parse(value.trim()).hasAssignableHosts();
        } catch (IllegalArgumentException e) {
            // Not this validator's job — @ValidCidr reports malformed CIDRs.
            return true;
        }
    }
}
