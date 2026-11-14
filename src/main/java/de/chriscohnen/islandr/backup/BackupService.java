package de.chriscohnen.islandr.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.chriscohnen.islandr.admin.ConfigExportDto;
import de.chriscohnen.islandr.admin.ConfigService;
import de.chriscohnen.islandr.crypto.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.concurrent.locks.ReentrantLock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Backups to and from an S3-compatible bucket (backup-s3): the same export as
 * {@code GET /admin/config/export}, encrypted with the admin's backup key
 * ({@link BackupCrypto}), written under a name of its own and never overwritten
 * or deleted ({@link S3Client} has no delete).
 *
 * <p>Restoring needs nothing from the old hub: the bucket, an access key and
 * the written-down backup key are enough, so a freshly installed instance can
 * be brought back from them alone.
 */
@ApplicationScoped
public class BackupService {

    static final String DEFAULT_PREFIX = "islandr-backups/";
    private static final String SUFFIX = ".islbak";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    // islandr-<host>-<UTC stamp>-<version>.islbak
    private static final Pattern NAME = Pattern.compile("islandr-(.+)-(\\d{8}T\\d{6}Z)-(.+)\\.islbak$");

    @Inject EncryptionService encryption;
    @Inject ConfigService configService;
    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    // ── settings ────────────────────────────────────────────────────────

    // joiningExisting: also callable from run(), which has no transaction of its own
    BackupConfig load() {
        return io.quarkus.narayana.jta.QuarkusTransaction.joiningExisting().call(() -> {
            BackupConfig c = BackupConfig.findById(BackupConfig.SINGLETON_ID);
            if (c == null) {
                c = new BackupConfig();
                c.persist();
            }
            return c;
        });
    }

    private String protect(String v) {
        if (v == null || v.isBlank()) return null;
        return encryption.isConfigured() ? encryption.encrypt(v) : v;
    }

    private String reveal(String stored) {
        if (stored == null) return null;
        return encryption.isEncrypted(stored) ? encryption.decrypt(stored) : stored;
    }

    @Transactional
    public BackupDto.Status status() {
        BackupConfig c = load();
        return new BackupDto.Status(c.endpoint, c.region, c.bucket, c.prefix, c.pathStyle,
                c.accessKey == null ? null : reveal(c.accessKey), c.secretKey != null, c.backupKey != null,
                encryption.isConfigured(), c.updatedAt,
                c.schedule, c.scheduleTime, c.scheduleDay, ZoneId.systemDefault().getId(),
                c.lastRunAt, c.lastRunOk, c.lastRunMessage);
    }

    @Transactional
    public BackupDto.Status save(BackupDto.ConnRequest req) {
        BackupConfig c = load();
        String endpoint = requireEndpoint(req.endpoint());
        c.endpoint = endpoint;
        c.region = blankTo(req.region(), "us-east-1");
        c.bucket = requireText(req.bucket(), "bucket");
        c.prefix = normalizePrefix(req.prefix());
        c.pathStyle = req.pathStyle() == null || req.pathStyle();
        c.accessKey = protect(requireText(req.accessKey(), "access key"));
        if (req.secretKey() != null && !req.secretKey().isBlank()) c.secretKey = protect(req.secretKey().strip());
        if (c.secretKey == null) throw new BadRequestException("the secret key is required");
        c.updatedAt = Instant.now();
        return status();
    }

    /** Makes a new key, stores it, and returns it in the form to write down. The caller shows it once. */
    @Transactional
    public String generateKey(boolean replace) {
        BackupConfig c = load();
        if (c.backupKey != null && !replace) {
            throw new ClientErrorException("a backup key already exists — replacing it makes every backup made "
                    + "with the old one unreadable unless you still have it written down", Response.Status.CONFLICT);
        }
        String written = BackupCrypto.formatKey(BackupCrypto.generateKey());
        c.backupKey = protect(written);
        c.updatedAt = Instant.now();
        return written;
    }

    // ── backup ──────────────────────────────────────────────────────────

    /** One backup at a time, whether an admin clicked or the schedule fired. */
    private final ReentrantLock running = new ReentrantLock();

    /** Sets the schedule; switching it on starts the clock now, it does not catch up on today's slot. */
    @Transactional
    public BackupDto.Status saveSchedule(BackupDto.ScheduleRequest req) {
        BackupConfig c = load();
        String schedule = req.schedule() == null ? BackupSchedule.OFF : req.schedule().strip().toLowerCase();
        if (!List.of(BackupSchedule.OFF, BackupSchedule.DAILY, BackupSchedule.WEEKLY).contains(schedule)) {
            throw new BadRequestException("the schedule is off, daily or weekly");
        }
        String time = blankTo(req.time(), "03:00");
        try {
            time = LocalTime.parse(time).toString();
        } catch (DateTimeParseException e) {
            throw new BadRequestException("the time must look like 03:00");
        }
        int day = req.dayOfWeek() == null ? 1 : req.dayOfWeek();
        if (day < 1 || day > 7) throw new BadRequestException("the day of the week is 1 (Monday) to 7 (Sunday)");
        if (!schedule.equals(BackupSchedule.OFF) && c.backupKey == null) {
            throw new BadRequestException("generate a backup key first");
        }
        boolean changed = !schedule.equals(c.schedule) || !time.equals(c.scheduleTime) || day != c.scheduleDay;
        c.schedule = schedule;
        c.scheduleTime = time.length() == 5 ? time : time.substring(0, 5);
        c.scheduleDay = day;
        if (changed) c.scheduleSince = Instant.now();
        return status();
    }

    /** True when the schedule says a backup is due right now. */
    boolean scheduledBackupDue(Instant now) {
        BackupConfig c = load();
        return BackupSchedule.due(now, c.schedule, LocalTime.parse(c.scheduleTime), c.scheduleDay,
                ZoneId.systemDefault(), c.scheduleSince, c.lastRunAt);
    }

    /** Runs a backup and remembers how it went, so the page can say. */
    public BackupDto.RunResult run() {
        if (!running.tryLock()) {
            throw new ClientErrorException("a backup is already running", Response.Status.CONFLICT);
        }
        try {
            BackupDto.RunResult r = runLocked();
            recordRun(true, r.name());
            return r;
        } catch (RuntimeException e) {
            recordRun(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        } finally {
            running.unlock();
        }
    }

    private void recordRun(boolean ok, String message) {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            BackupConfig c = load();
            c.lastRunAt = Instant.now();
            c.lastRunOk = ok;
            c.lastRunMessage = message.length() > 1000 ? message.substring(0, 1000) : message;
        });
    }

    private BackupDto.RunResult runLocked() {
        BackupConfig c = load();
        if (c.backupKey == null) throw new BadRequestException("generate a backup key first");
        S3Client.Conn conn = storedConn(c);
        byte[] key = BackupCrypto.parseKey(reveal(c.backupKey));

        byte[] json;
        try {
            // Everything, private keys included: the file is encrypted, and a restore
            // that quietly lacks the stored device keys would not be a restore.
            json = mapper.writeValueAsBytes(configService.export(true));
        } catch (IOException e) {
            throw new IllegalStateException("could not write the export", e);
        }
        Instant now = Instant.now();
        String host = hostName();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("format", 1);
        header.put("createdAt", now.toString());
        header.put("host", host);
        header.put("islandr", appVersion);
        header.put("exportVersion", ConfigExportDto.CURRENT_VERSION);
        byte[] file = BackupCrypto.seal(json, key, header, mapper);

        String name = prefixOf(c.prefix) + "islandr-" + safe(host) + "-" + STAMP.format(now) + "-" + safe(appVersion) + SUFFIX;
        translate(() -> new S3Client(conn).put(name, file));
        return new BackupDto.RunResult(name, file.length);
    }

    public BackupDto.Listing list(BackupDto.ConnRequest override) {
        BackupConfig c = load();
        S3Client.Conn conn = override == null ? storedConn(c) : overrideConn(override);
        String prefix = prefixOf(override == null ? c.prefix : override.prefix());
        List<S3Client.Entry> entries = translate(() -> new S3Client(conn).list(prefix));
        return new BackupDto.Listing(entries.stream()
                .filter(e -> e.key().endsWith(SUFFIX))
                .map(this::describe)
                .toList());
    }

    private BackupDto.Backup describe(S3Client.Entry e) {
        String base = e.key().substring(e.key().lastIndexOf('/') + 1);
        Matcher m = NAME.matcher(base);
        String host = null, version = null;
        Instant created = null;
        if (m.find()) {
            host = m.group(1);
            version = m.group(3);
            created = Instant.from(STAMP.parse(m.group(2)));
        }
        return new BackupDto.Backup(e.key(), e.size(), e.lastModified(), host, version, created);
    }

    /** Opens a backup and imports it. Nothing changes unless the key opens the file. */
    public ConfigExportDto.ImportResult restore(BackupDto.RestoreRequest req) {
        BackupConfig c = load();
        S3Client.Conn conn = req.conn() == null ? storedConn(c) : overrideConn(req.conn());
        String name = requireText(req.name(), "backup name");
        String prefix = prefixOf(req.conn() == null ? c.prefix : req.conn().prefix());
        if (!name.startsWith(prefix) || !name.endsWith(SUFFIX)) {
            throw new BadRequestException("that is not a backup in this folder");
        }
        byte[] key;
        try {
            key = BackupCrypto.parseKey(req.key());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        byte[] file = translate(() -> new S3Client(conn).get(name));
        byte[] json;
        try {
            json = BackupCrypto.open(file, key);
        } catch (BackupCrypto.WrongKeyException e) {
            throw new BadRequestException(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        ConfigExportDto.Export export;
        try {
            export = mapper.readValue(json, ConfigExportDto.Export.class);
        } catch (IOException e) {
            throw new BadRequestException("the backup opened, but its content is not readable by this version");
        }
        return configService.importConfig(export);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private S3Client.Conn storedConn(BackupConfig c) {
        if (c.endpoint == null || c.bucket == null || c.accessKey == null || c.secretKey == null) {
            throw new BadRequestException("set up the bucket first");
        }
        return new S3Client.Conn(c.endpoint, blankTo(c.region, "us-east-1"), c.bucket,
                reveal(c.accessKey), reveal(c.secretKey), c.pathStyle);
    }

    private S3Client.Conn overrideConn(BackupDto.ConnRequest r) {
        return new S3Client.Conn(requireEndpoint(r.endpoint()), blankTo(r.region(), "us-east-1"),
                requireText(r.bucket(), "bucket"), requireText(r.accessKey(), "access key"),
                requireText(r.secretKey(), "secret key"), r.pathStyle() == null || r.pathStyle());
    }

    private static <T> T translate(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (S3Client.S3Exception e) {
            // 502: the bucket, not this request, said no — the message is the bucket's own
            String why = "the bucket refused or could not be reached: " + e.getMessage();
            // message set explicitly: it is what the status line and the audit log record
            throw new jakarta.ws.rs.WebApplicationException(why,
                    Response.status(Response.Status.BAD_GATEWAY).entity(why).build());
        }
    }

    private static void translate(Runnable call) {
        translate(() -> { call.run(); return null; });
    }

    private static String requireEndpoint(String endpoint) {
        String e = requireText(endpoint, "endpoint");
        try {
            URI u = URI.create(e);
            if (u.getHost() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http"))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new BadRequestException("the endpoint must be a URL such as https://s3.example.com");
        }
        return e.replaceAll("/+$", "");
    }

    private static String requireText(String v, String what) {
        if (v == null || v.isBlank()) throw new BadRequestException("the " + what + " is required");
        return v.strip();
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    /** "folder" and "/folder//" both become "folder/"; empty falls back to the default folder. */
    static String normalizePrefix(String p) {
        if (p == null || p.isBlank()) return DEFAULT_PREFIX;
        String s = p.strip().replaceAll("^/+", "").replaceAll("/+$", "");
        return s.isEmpty() ? "" : s + "/";
    }

    private static String prefixOf(String stored) {
        return stored == null ? DEFAULT_PREFIX : normalizePrefix(stored);
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._]", "_");
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "hub";
        }
    }
}
