package de.chriscohnen.islandr.backup;

import com.sun.net.httpserver.HttpServer;
import de.chriscohnen.islandr.auth.AdminSessionExtension;
import de.chriscohnen.islandr.user.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import jakarta.inject.Inject;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * backup-s3 end to end against a fake bucket: set up, make a key, back up,
 * list, restore — and the two properties that matter most when it goes wrong:
 * a wrong key changes nothing, and a fresh instance can reach the bucket from
 * nothing but what the admin wrote down.
 */
@QuarkusTest
@ExtendWith(AdminSessionExtension.class)
class BackupResourceTest {

    private static final String MARK = "backup-s3-";

    @Inject BackupScheduler scheduler;
    /** When set, a PUT waits on it — to hold a backup in flight. */
    private volatile CountDownLatch putGate;

    private HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> methods = new ArrayList<>();
    private int port;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            methods.add(ex.getRequestMethod());
            String path = ex.getRequestURI().getRawPath();
            byte[] out = new byte[0];
            int status = 200;
            if (ex.getRequestMethod().equals("PUT")) {
                CountDownLatch gate = putGate;
                if (gate != null) { try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
                objects.put(path, ex.getRequestBody().readAllBytes());
            } else if (path.equals("/bucket") || path.equals("/bucket/")) {
                StringBuilder xml = new StringBuilder("<ListBucketResult><IsTruncated>false</IsTruncated>");
                objects.forEach((k, v) -> xml.append("<Contents><Key>").append(k.substring("/bucket/".length()))
                        .append("</Key><Size>").append(v.length)
                        .append("</Size><LastModified>2026-11-14T12:00:00.000Z</LastModified></Contents>"));
                out = xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8);
            } else if (objects.containsKey(path)) {
                out = objects.get(path);
            } else {
                status = 404;
                out = "<Error><Code>NoSuchKey</Code><Message>gone</Message></Error>".getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        QuarkusTransaction.requiringNew().run(() -> {
            BackupConfig.deleteAll();
            User.delete("email like ?1", MARK + "%");
        });
    }

    private String conn() {
        return "{\"endpoint\":\"http://127.0.0.1:" + port + "\",\"region\":\"auto\",\"bucket\":\"bucket\","
                + "\"prefix\":\"\",\"accessKey\":\"AKID\",\"secretKey\":\"SECRET\"}";
    }

    private void configure() {
        given().contentType("application/json").body(conn())
                .when().put("/api/v1/admin/backup/config").then().statusCode(200);
    }

    private String newKey() {
        return given().contentType("application/json").body("{}")
                .when().post("/api/v1/admin/backup/key").then().statusCode(200)
                .body("key", matchesPattern("IBK1(-[A-Z2-7]{4}){14}"))
                .extract().path("key");
    }

    private String backUp() {
        return given().contentType("application/json").when().post("/api/v1/admin/backup/run").then().statusCode(200)
                .body("name", matchesPattern("islandr-backups/islandr-.+-\\d{8}T\\d{6}Z-.+\\.islbak"))
                .extract().path("name");
    }

    @Test
    void statusNeverShowsASecret() {
        configure();
        String body = given().when().get("/api/v1/admin/backup").then().statusCode(200)
                .body("secretKeySet", is(true))
                .body("backupKeySet", is(false))
                .body("accessKey", is("AKID"))
                .extract().asString();
        assertThat(body).doesNotContain("SECRET");
    }

    @Test
    void backingUpNeedsAKeyFirst() {
        configure();
        given().contentType("application/json").when().post("/api/v1/admin/backup/run").then().statusCode(400)
                .body(containsString("backup key"));
    }

    @Test
    void aSecondKeyIsRefusedUnlessReplacingIsExplicit() {
        newKey();
        given().contentType("application/json").body("{}")
                .when().post("/api/v1/admin/backup/key").then().statusCode(409);
        given().contentType("application/json").body("{\"replace\":true}")
                .when().post("/api/v1/admin/backup/key").then().statusCode(200);
    }

    @Test
    void backupThenListThenRestore_roundTrips_andNeverSendsADelete() {
        configure();
        String key = newKey();
        String name = backUp();

        given().contentType("application/json").body("{}")
                .when().post("/api/v1/admin/backup/list").then().statusCode(200)
                .body("backups", hasSize(1))
                .body("backups[0].name", is(name))
                .body("backups[0].islandrVersion", notNullValue());

        given().contentType("application/json").body("{\"name\":\"" + name + "\",\"key\":\"" + key + "\"}")
                .when().post("/api/v1/admin/backup/restore").then().statusCode(200)
                .body("users", notNullValue());

        assertThat(methods).doesNotContain("DELETE");
    }

    @Test
    void aWrongKeyIsRefusedAndTheConfigurationIsLeftAlone() {
        configure();
        newKey();
        String name = backUp();
        // a user made after the backup must still be there after a refused restore
        QuarkusTransaction.requiringNew().run(() -> User.createNew("After", MARK + "after@example.test").persist());
        String otherKey = BackupCrypto.formatKey(BackupCrypto.generateKey());

        given().contentType("application/json").body("{\"name\":\"" + name + "\",\"key\":\"" + otherKey + "\"}")
                .when().post("/api/v1/admin/backup/restore").then().statusCode(400)
                .body(containsString("wrong backup key"));

        assertThat(QuarkusTransaction.requiringNew().call(() -> User.count("email", MARK + "after@example.test")))
                .isEqualTo(1);
    }

    @Test
    void aMistypedKeyIsCaughtBeforeTheBucketIsAsked() {
        configure();
        newKey();
        String name = backUp();
        methods.clear();
        given().contentType("application/json").body("{\"name\":\"" + name + "\",\"key\":\"IBK1-ABCD\"}")
                .when().post("/api/v1/admin/backup/restore").then().statusCode(400);
        assertThat(methods).isEmpty();
    }

    /** Bootstrap: an instance that was never configured reaches the bucket with the connection in the request. */
    @Test
    void aFreshInstanceCanListAndRestoreFromTheBucketAlone() {
        configure();
        String key = newKey();
        String name = backUp();
        // forget everything this instance knows about the bucket and the key
        QuarkusTransaction.requiringNew().run(() -> BackupConfig.deleteAll());

        given().contentType("application/json").body("{}")
                .when().post("/api/v1/admin/backup/list").then().statusCode(400);

        given().contentType("application/json").body("{\"conn\":" + conn() + "}")
                .when().post("/api/v1/admin/backup/list").then().statusCode(200)
                .body("backups", hasSize(1));

        given().contentType("application/json")
                .body("{\"conn\":" + conn() + ",\"name\":\"" + name + "\",\"key\":\"" + key + "\"}")
                .when().post("/api/v1/admin/backup/restore").then().statusCode(200);
    }

    @Test
    void aBucketThatSaysNoIsReportedWithItsOwnWords() {
        configure();
        newKey();
        backUp();
        given().contentType("application/json")
                .body("{\"name\":\"islandr-backups/islandr-x-20260101T000000Z-1.islbak\",\"key\":\""
                        + BackupCrypto.formatKey(BackupCrypto.generateKey()) + "\"}")
                .when().post("/api/v1/admin/backup/restore").then().statusCode(502)
                .body(containsString("NoSuchKey"));
    }

    @Test
    void theEndpointMustBeAUrl() {
        given().contentType("application/json")
                .body("{\"endpoint\":\"not a url\",\"bucket\":\"b\",\"accessKey\":\"a\",\"secretKey\":\"s\"}")
                .when().put("/api/v1/admin/backup/config").then().statusCode(400);
    }

    // ── backup-schedule ─────────────────────────────────────────────────

    private void schedule(String body) {
        given().contentType("application/json").body(body)
                .when().put("/api/v1/admin/backup/schedule").then().statusCode(200);
    }

    @Test
    void aScheduleNeedsAKey_andSensibleValues() {
        configure();
        given().contentType("application/json").body("{\"schedule\":\"daily\",\"time\":\"03:00\"}")
                .when().put("/api/v1/admin/backup/schedule").then().statusCode(400).body(containsString("backup key"));
        newKey();
        given().contentType("application/json").body("{\"schedule\":\"hourly\"}")
                .when().put("/api/v1/admin/backup/schedule").then().statusCode(400);
        given().contentType("application/json").body("{\"schedule\":\"daily\",\"time\":\"25:99\"}")
                .when().put("/api/v1/admin/backup/schedule").then().statusCode(400);
        given().contentType("application/json").body("{\"schedule\":\"weekly\",\"dayOfWeek\":9}")
                .when().put("/api/v1/admin/backup/schedule").then().statusCode(400);
        schedule("{\"schedule\":\"weekly\",\"time\":\"04:30\",\"dayOfWeek\":3}");
        given().when().get("/api/v1/admin/backup").then()
                .body("schedule", is("weekly")).body("scheduleTime", is("04:30")).body("scheduleDay", is(3))
                .body("zone", notNullValue());
    }

    @Test
    void aDueScheduleRunsOnce_andTheStatusSaysSo() {
        configure();
        newKey();
        schedule("{\"schedule\":\"daily\",\"time\":\"03:00\"}");
        // pretend it was switched on three days ago, so a slot has passed since
        QuarkusTransaction.requiringNew().run(() ->
                BackupConfig.<BackupConfig>findById(BackupConfig.SINGLETON_ID).scheduleSince =
                        java.time.Instant.now().minus(java.time.Duration.ofDays(3)));

        scheduler.runIfDue(java.time.Instant.now());
        assertThat(objects).hasSize(1);
        given().when().get("/api/v1/admin/backup").then()
                .body("lastRunOk", is(true)).body("lastRunMessage", containsString(".islbak"));

        scheduler.runIfDue(java.time.Instant.now());   // nothing new is due
        assertThat(objects).hasSize(1);
    }

    @Test
    void aFailedScheduledRunIsRecordedWithTheReason() {
        configure();
        newKey();
        schedule("{\"schedule\":\"daily\",\"time\":\"03:00\"}");
        QuarkusTransaction.requiringNew().run(() -> {
            BackupConfig c = BackupConfig.findById(BackupConfig.SINGLETON_ID);
            c.scheduleSince = java.time.Instant.now().minus(java.time.Duration.ofDays(3));
            c.endpoint = "http://127.0.0.1:1";   // nothing listens there
        });

        scheduler.runIfDue(java.time.Instant.now());

        given().when().get("/api/v1/admin/backup").then()
                .body("lastRunOk", is(false)).body("lastRunMessage", containsString("could not be reached"));
    }

    @Test
    void aBackupAlreadyRunningRefusesASecondOne() throws Exception {
        configure();
        newKey();
        putGate = new CountDownLatch(1);
        Thread first = new Thread(() -> given().contentType("application/json").when().post("/api/v1/admin/backup/run"));
        first.start();
        try {
            // wait until the first PUT is in flight, then ask again
            for (int i = 0; i < 100 && methods.isEmpty(); i++) Thread.sleep(50);
            given().contentType("application/json").when().post("/api/v1/admin/backup/run").then().statusCode(409);
        } finally {
            putGate.countDown();
            first.join(15000);
            putGate = null;
        }
    }
}
