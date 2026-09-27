package de.chriscohnen.islandr.auth;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * A security-key ceremony that does not complete is a refused authentication,
 * not a server fault.
 *
 * <p>Without this mapper every refusal — an expired challenge, a console
 * reached by IP, a response that answers a different ceremony than the one
 * that was started — left the endpoint as an unhandled runtime exception and
 * answered 500. That is wrong twice over: the caller cannot tell "you were
 * turned away" from "this hub is broken", and a stack trace on an
 * unauthenticated path is noise that hides the entries worth reading.
 */
@Provider
public class CeremonyFailedMapper implements ExceptionMapper<WebAuthnService.CeremonyFailedException> {

    @Override
    public Response toResponse(WebAuthnService.CeremonyFailedException e) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("message", e.getMessage() == null ? "ceremony failed" : e.getMessage()))
                .build();
    }
}
