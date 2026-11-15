package de.chriscohnen.islandr.backup;

import de.chriscohnen.islandr.admin.ConfigExportDto;
import de.chriscohnen.islandr.audit.AuditService;
import de.chriscohnen.islandr.auth.Auth;
import de.chriscohnen.islandr.auth.AuthContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * Backups to an S3-compatible bucket (backup-s3). Admin only, and deliberately
 * not an API-key scope: it moves the whole configuration, secrets included.
 */
@Path("/api/v1/admin/backup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BackupResource {

    @Inject BackupService backups;
    @Inject AuditService audit;

    @GET
    public BackupDto.Status status(@Context ContainerRequestContext ctx) {
        Auth.requireAdmin(ctx);
        return backups.status();
    }

    @PUT
    @Path("/config")
    public BackupDto.Status save(@Context ContainerRequestContext ctx, BackupDto.ConnRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        if (body == null) throw new BadRequestException("request body required");
        BackupDto.Status s = backups.save(body);
        audit.logUpdate(a.principal(), "backup.config", "Backup:config", Map.of(),
                Map.of("endpoint", String.valueOf(s.endpoint()), "bucket", String.valueOf(s.bucket())));
        return s;
    }

    /** The key is in the response once — there is no endpoint that shows it again. */
    @POST
    @Path("/key")
    public BackupDto.NewKey newKey(@Context ContainerRequestContext ctx, BackupDto.KeyRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        boolean replace = body != null && Boolean.TRUE.equals(body.replace());
        String key = backups.generateKey(replace);
        audit.logEvent(a.principal(), "backup.key", "Backup:key", Map.of("replaced", replace));
        return new BackupDto.NewKey(key);
    }

    @PUT
    @Path("/schedule")
    public BackupDto.Status schedule(@Context ContainerRequestContext ctx, BackupDto.ScheduleRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        if (body == null) throw new BadRequestException("request body required");
        BackupDto.Status s = backups.saveSchedule(body);
        audit.logEvent(a.principal(), "backup.schedule", "Backup:schedule", Map.of("schedule", s.schedule(), "time", s.scheduleTime()));
        return s;
    }

    @POST
    @Path("/run")
    public BackupDto.RunResult run(@Context ContainerRequestContext ctx) {
        AuthContext a = Auth.requireAdmin(ctx);
        BackupDto.RunResult r = backups.run();
        audit.logEvent(a.principal(), "backup.run", "Backup:" + r.name(), Map.of("size", r.size()));
        return r;
    }

    @POST
    @Path("/list")
    public BackupDto.Listing list(@Context ContainerRequestContext ctx, BackupDto.ListRequest body) {
        Auth.requireAdmin(ctx);
        return backups.list(body == null ? null : body.conn());
    }

    @POST
    @Path("/restore")
    public ConfigExportDto.ImportResult restore(@Context ContainerRequestContext ctx, BackupDto.RestoreRequest body) {
        AuthContext a = Auth.requireAdmin(ctx);
        if (body == null) throw new BadRequestException("request body required");
        ConfigExportDto.ImportResult r = backups.restore(body);
        audit.logEvent(a.principal(), "backup.restore", "Backup:" + body.name(), Map.of("users", r.users(), "peers", r.peers()));
        return r;
    }
}
