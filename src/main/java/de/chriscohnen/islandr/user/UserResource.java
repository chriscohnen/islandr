package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.firewall.RulesetService;
import io.quarkus.panache.common.Sort;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/v1/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject AuditService audit;
    @Inject RulesetService rulesets;
    @Inject de.chriscohnen.islandr.crypto.PasswordHasher passwordHasher;

    /** Returns the profile of the currently authenticated user. Available to all logged-in users. */
    @GET
    @Path("/me")
    public UserDto.Response me(@Context ContainerRequestContext ctx) {
        AuthContext a = Auth.require(ctx);
        if (a.userId() == null) {
            // Local ENV-admin has no User row — return a synthetic minimal response.
            return new UserDto.Response(null, a.principal(), null, a.principal(),
                    null, true, true, null, 0, null);
        }
        User u = User.findById(a.userId());
        if (u == null) throw new jakarta.ws.rs.NotFoundException("user not found");
        return UserDto.Response.from(u);
    }

    /** Stores the user's explicit language preference. Available to all logged-in users. */
    @PUT
    @Path("/me/locale")
    @Transactional
    public UserDto.Response setMyLocale(@Context ContainerRequestContext ctx, UserDto.LocaleRequest body) {
        AuthContext a = Auth.require(ctx);
        if (a.userId() == null) return me(ctx);  // local admin — no-op
        User u = User.findById(a.userId());
        if (u == null) throw new jakarta.ws.rs.NotFoundException("user not found");
        String newLocale = (body != null && body.locale() != null && !body.locale().isBlank())
                ? body.locale().trim().toLowerCase() : null;
        if (newLocale != null && !newLocale.equals("de") && !newLocale.equals("en")) {
            throw new jakarta.ws.rs.BadRequestException("locale must be 'de' or 'en'");
        }
        u.preferredLocale = newLocale;
        return UserDto.Response.from(u);
    }

    @GET
    public List<UserDto.Response> list(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return User.<User>listAll(Sort.by("createdAt").descending())
                .stream()
                .map(UserDto.Response::from)
                .toList();
    }

    @GET
    @Path("/{id}")
    public UserDto.Response get(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) {
            throw new NotFoundException("user not found: " + id);
        }
        return UserDto.Response.from(u);
    }

    @POST
    @Transactional
    public Response create(@Context ContainerRequestContext ctx, @Valid UserDto.CreateRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.createNew(body.name(), body.email());
        u.persist();
        audit.logCreate(a.principal(), "user.create", "User:" + u.name + " (" + u.id + ")",
                Map.of("name", u.name, "email", u.email, "isAdmin", u.isAdmin));
        return Response
                .created(UriBuilder.fromResource(UserResource.class).path(u.id).build())
                .entity(UserDto.Response.from(u))
                .build();
    }

    /**
     * Update a user's identity fields: name and email. Email is the local-login
     * username (F-01a) and is globally unique — a collision with another user
     * returns 409. OIDC users are matched by subject, so an email change here is
     * safe (the IdP re-asserts its own email on the next login).
     */
    @PUT
    @Path("/{id}")
    @Transactional
    public UserDto.Response update(@Context ContainerRequestContext ctx,
                                   @PathParam("id") String id,
                                   @Valid UserDto.UpdateRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        String name = body.name().strip();
        String email = body.email().strip();
        if (User.count("email = ?1 and id <> ?2", email, id) > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("email already in use: " + email)
                            .build());
        }
        Map<String, Object> before = Map.of("name", u.name, "email", u.email);
        u.name = name;
        u.email = email;
        audit.logUpdate(a.principal(), "user.update", "User:" + u.name + " (" + u.id + ")",
                before, Map.of("name", u.name, "email", u.email));
        return UserDto.Response.from(u);
    }

    @PUT
    @Path("/{id}/enabled")
    @Transactional
    public UserDto.Response setEnabled(@Context ContainerRequestContext ctx,
                                       @PathParam("id") String id,
                                       UserDto.EnabledRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        if (a.userId() != null && a.userId().equals(id)) {
            throw new jakarta.ws.rs.BadRequestException("cannot disable your own account");
        }
        boolean wanted = body != null && body.enabled();
        if (u.enabled == wanted) return UserDto.Response.from(u);
        u.enabled = wanted;
        String action = wanted ? "user.enable" : "user.disable";
        audit.logUpdate(a.principal(), action, "User:" + u.name + " (" + u.id + ")",
                Map.of("enabled", !wanted), Map.of("enabled", wanted));
        return UserDto.Response.from(u);
    }

    @PUT
    @Path("/{id}/admin")
    @Transactional
    public UserDto.Response setAdmin(@Context ContainerRequestContext ctx,
                                     @PathParam("id") String id,
                                     UserDto.AdminFlagRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        boolean wanted = body != null && body.isAdmin();
        if (u.isAdmin == wanted) return UserDto.Response.from(u);  // no-op, no audit
        u.isAdmin = wanted;
        // Two distinct actions on the same column so filtering on
        // "show me every privilege escalation" is one WHERE clause, not two.
        String action = wanted ? "user.admin_grant" : "user.admin_revoke";
        audit.logUpdate(a.principal(), action, "User:" + u.name + " (" + u.id + ")",
                Map.of("isAdmin", !wanted), Map.of("isAdmin", wanted));
        return UserDto.Response.from(u);
    }

    /**
     * Set (non-blank, min 8 chars) or clear (blank) a user's local password (F-01a).
     * Setting enables local email+password login; clearing disables it (OIDC/ENV only).
     * The password is never echoed back and never audited in plaintext.
     */
    @PUT
    @Path("/{id}/password")
    @Transactional
    public UserDto.Response setPassword(@Context ContainerRequestContext ctx,
                                        @PathParam("id") String id,
                                        UserDto.PasswordRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        String pw = body != null ? body.password() : null;
        if (pw == null || pw.isBlank()) {
            if (u.passwordHash == null) return UserDto.Response.from(u); // no-op, no audit
            u.passwordHash = null;
            audit.logUpdate(a.principal(), "user.password_reset", "User:" + u.name + " (" + u.id + ")",
                    Map.of("hadPassword", true), Map.of("hadPassword", false));
            return UserDto.Response.from(u);
        }
        if (pw.length() < 8) {
            throw new jakarta.ws.rs.BadRequestException("password must be at least 8 characters");
        }
        u.passwordHash = passwordHasher.hash(pw);
        audit.logUpdate(a.principal(), "user.password_set", "User:" + u.name + " (" + u.id + ")",
                Map.of(), Map.of("passwordSet", true));
        return UserDto.Response.from(u);
    }

    @PUT
    @Path("/{id}/nickname")
    @Transactional
    public UserDto.Response setNickname(@Context ContainerRequestContext ctx,
                                        @PathParam("id") String id,
                                        UserDto.NicknameRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        String prev = u.nickname;
        u.nickname = (body != null && body.nickname() != null && !body.nickname().isBlank())
                ? body.nickname().trim() : null;
        audit.logUpdate(a.principal(), "user.nickname_set", "User:" + u.id,
                Map.of("nickname", prev == null ? "" : prev),
                Map.of("nickname", u.nickname == null ? "" : u.nickname));
        return UserDto.Response.from(u);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@Context ContainerRequestContext ctx, @PathParam("id") String id) {
        AuthContext a = Auth.requireAdmin(ctx);
        User u = User.findById(id);
        if (u == null) throw new NotFoundException("user not found: " + id);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", u.name);
        before.put("email", u.email);
        before.put("isAdmin", u.isAdmin);
        u.delete();
        audit.logDelete(a.principal(), "user.delete", "User:" + u.name + " (" + id + ")", before);
        // FK cascade kills peers and user_roles rows; the user-bound accept
        // rules need to vanish from nftables.
        rulesets.recomputeFromHook();
        return Response.noContent().build();
    }
}
