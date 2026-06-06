package de.chriscohnen.islandr.auth;

import de.chriscohnen.islandr.user.User;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/**
 * Dev-only endpoint: creates a session for any user by ID and sets the session
 * cookie. Used by the Playwright screenshot script to log in as a non-admin user
 * without OIDC. Not compiled into prod builds (@IfBuildProfile("dev")).
 */
@Path("/api/v1/dev/session")
@Produces(MediaType.APPLICATION_JSON)
@IfBuildProfile("dev")
public class DevSessionResource {

    @Inject SessionService sessions;

    @GET
    @Path("/{userId}")
    public Response sessionFor(@PathParam("userId") String userId) {
        User u = User.findById(userId);
        if (u == null) {
            return Response.status(404).entity(java.util.Map.of("error", "user not found")).build();
        }
        Session s = sessions.create("google", u.email, u.id);
        NewCookie cookie = new NewCookie.Builder(SessionFilter.COOKIE_NAME)
                .value(s.id)
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge((int) SessionService.TTL.toSeconds())
                .build();
        return Response.ok(java.util.Map.of("sessionId", s.id)).cookie(cookie).build();
    }
}
