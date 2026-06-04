package de.chriscohnen.islandr.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class UserDto {

    public record Response(
            String id,
            String name,
            String nickname,
            String displayName,
            String email,
            boolean enabled,
            boolean isAdmin,
            Instant createdAt
    ) {
        public static Response from(User u) {
            String display = (u.nickname != null && !u.nickname.isBlank()) ? u.nickname : u.name;
            return new Response(u.id, u.name, u.nickname, display, u.email, u.enabled, u.isAdmin, u.createdAt);
        }
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank @Email String email
    ) {}

    public record AdminFlagRequest(boolean isAdmin) {}

    public record NicknameRequest(String nickname) {}

    private UserDto() {}
}
