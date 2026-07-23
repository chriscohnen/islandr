package de.chriscohnen.islandr.acme;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The RFC 8555 HTTP-01 challenge response path — fixed by the spec, not
 * an islandr routing choice: {@code /.well-known/acme-challenge/{token}}.
 * Deliberately unauthenticated (no {@code Auth.require} call): Let's
 * Encrypt's own validation servers hit this, not a logged-in admin.
 *
 * <p>This is the entirety of risk R-164 (§8.1 T-016): it answers only with
 * whatever {@link ChallengeHolder} currently holds, and only for the one
 * token actually in flight — every other request, including this same path
 * outside an active issuance attempt, gets a plain 404.
 */
@jakarta.ws.rs.Path("/.well-known/acme-challenge")
public class AcmeChallengeResource {

    @Inject ChallengeHolder challenges;

    @GET
    @jakarta.ws.rs.Path("/{token}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response respond(@PathParam("token") String token) {
        String keyAuthorization = challenges.keyAuthorizationFor(token);
        if (keyAuthorization == null) {
            throw new NotFoundException();
        }
        return Response.ok(keyAuthorization).build();
    }
}
