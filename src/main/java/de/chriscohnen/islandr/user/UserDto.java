package de.chriscohnen.islandr.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class UserDto {

    public record Response(
            String id,
            String name,
            String email,
            boolean enabled,
            boolean isAdmin,
            Instant createdAt
    ) {
        public static Response from(User u) {
            return new Response(u.id, u.name, u.email, u.enabled, u.isAdmin, u.createdAt);
        }
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank @Email String email
    ) {}

    public record AdminFlagRequest(boolean isAdmin) {}

    private UserDto() {}
}
