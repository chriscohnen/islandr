package de.chriscohnen.islandr.acl;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class UserGrantDto {
    public record Update(
            @NotBlank String userId,
            @NotBlank String resourceId,
            boolean allPorts,
            List<String> portIds   // ignored when allPorts=true
    ) {}
}
