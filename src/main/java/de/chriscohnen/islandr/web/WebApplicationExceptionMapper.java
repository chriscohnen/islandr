package de.chriscohnen.islandr.web;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Puts a thrown exception's own message into the HTTP response body
 * (rest-error-body-empty) — without this, {@code new BadRequestException("no
 * user with that e-mail address")} and every sibling thrown with only a
 * message (never an explicit {@code Response}) reaches the caller as an empty
 * body: plain JAX-RS only stores that string in {@link Throwable#getMessage()},
 * never in the response entity, unless something puts it there.
 *
 * <p>Every self-service error banner in the frontend that does
 * {@code throw new Error(await res.text())} was silently showing "Error: "
 * with nothing after it for exactly this reason — confirmed on the
 * already-shipped {@code POST /api/v1/reservations} path, not something this
 * mapper's own feature introduced.
 *
 * <p><b>Never overrides an exception that already carries a body</b> — several
 * places in this codebase throw {@code new WebApplicationException(Response
 * .status(...).entity(message).build())} deliberately (e.g.
 * {@code ResourceService}'s {@code conflict()} helper); those already do the
 * right thing and must pass through unchanged.
 */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException exception) {
        Response original = exception.getResponse();
        if (original.hasEntity() || exception.getMessage() == null) {
            return original;
        }
        return Response.fromResponse(original)
                .type(MediaType.TEXT_PLAIN)
                .entity(exception.getMessage())
                .build();
    }
}
