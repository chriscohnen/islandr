package de.chriscohnen.islandr.user;

import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import de.chriscohnen.islandr.crypto.EncryptionService;
import de.chriscohnen.islandr.settings.Settings;
import de.chriscohnen.islandr.settings.SettingsService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/api/v1/users/import/google")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GoogleWorkspaceImportResource {

    private static final Logger LOG = Logger.getLogger(GoogleWorkspaceImportResource.class);

    @Inject SettingsService settingsSvc;
    @Inject GoogleWorkspaceClient gwsClient;
    @Inject AuditService audit;
    @Inject EncryptionService encSvc;

    public record PreviewUser(
            String email,
            String name,
            String avatarUrl,
            /** "new" | "existing" | "suspended" */
            String status
    ) {}

    public record PreviewResponse(boolean configured, List<PreviewUser> users) {}

    public record ImportRequest(List<String> emails) {}

    public record ImportResult(int imported, int skipped, List<String> errors) {}

    @GET
    public Response preview(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        Settings s = settingsSvc.get();
        if (s.googleWsServiceAccountJson == null || s.googleWsServiceAccountJson.isBlank()) {
            return Response.ok(new PreviewResponse(false, List.of())).build();
        }

        Set<String> existingEmails = User.<User>listAll().stream()
                .map(u -> u.email.toLowerCase())
                .collect(Collectors.toSet());

        String saJson = encSvc.isEncrypted(s.googleWsServiceAccountJson)
                ? encSvc.decrypt(s.googleWsServiceAccountJson)
                : s.googleWsServiceAccountJson;

        try {
            List<GoogleWorkspaceClient.WorkspaceUser> wsUsers =
                    gwsClient.listUsers(saJson, s.googleWsImpersonationEmail);

            List<PreviewUser> preview = wsUsers.stream()
                    .filter(u -> u.email() != null)
                    .map(u -> {
                        String status;
                        if (existingEmails.contains(u.email().toLowerCase())) {
                            status = "existing";
                        } else if (u.suspended()) {
                            status = "suspended";
                        } else {
                            status = "new";
                        }
                        return new PreviewUser(u.email(), u.name(), u.avatarUrl(), status);
                    })
                    .toList();

            return Response.ok(new PreviewResponse(true, preview)).build();
        } catch (Exception e) {
            LOG.warnf("Google Workspace preview failed: %s", e.getMessage());
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Transactional
    public ImportResult importUsers(@Context ContainerRequestContext ctx, ImportRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        if (body == null || body.emails() == null || body.emails().isEmpty()) {
            return new ImportResult(0, 0, List.of());
        }

        Set<String> existingEmails = User.<User>listAll().stream()
                .map(u -> u.email.toLowerCase())
                .collect(Collectors.toSet());

        int imported = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (String email : body.emails()) {
            if (email == null || email.isBlank()) continue;
            String normalised = email.strip().toLowerCase();
            if (existingEmails.contains(normalised)) {
                skipped++;
                continue;
            }
            try {
                // Derive display name from email prefix — the caller may have passed a
                // name in the request but the ImportRequest only carries emails. A
                // subsequent OIDC login will update the name from the id_token claim.
                String name = normalised.substring(0, normalised.indexOf('@'));
                User u = User.createNew(name, normalised);
                u.persist();
                existingEmails.add(normalised);
                audit.logCreate(a.principal(), "user.import_google",
                        "User:" + u.name + " (" + u.id + ")",
                        Map.of("email", u.email, "source", "google_workspace"));
                imported++;
            } catch (Exception e) {
                LOG.warnf("failed to import %s: %s", email, e.getMessage());
                errors.add(email + ": " + e.getMessage());
            }
        }

        return new ImportResult(imported, skipped, errors);
    }
}
