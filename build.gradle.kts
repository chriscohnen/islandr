plugins {
    java
    id("io.quarkus")
    jacoco
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

// When building SQLite-only (e.g. native), activate the "sqlite" profile to
// exclude the PostgreSQL driver (~10-12 MB in the native binary):
//   ./gradlew build -Psqlite -Dquarkus.native.enabled=true ...
val sqliteOnly = project.hasProperty("sqlite")

dependencies {
    // Quarkus BOM — keeps all extension versions aligned
    implementation(enforcedPlatform("$quarkusPlatformGroupId:$quarkusPlatformArtifactId:$quarkusPlatformVersion"))

    // WebAuthn engine only (ADR-0028): challenge generation, CBOR decoding,
    // attestation handling, signature and counter verification. Registration,
    // authentication and session issuance stay in islandr's own auth package —
    // quarkus-security-webauthn was rejected because it brings a second
    // authenticated-session mechanism with its own cookie, which would not
    // inherit the per-request access re-check from #53.
    // Pulls only vertx-auth-common besides vertx-core, which is already pinned
    // to 4.5.27 by the resolution rule below.
    implementation("io.vertx:vertx-auth-webauthn:4.5.27")

    // Web layer
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-websockets-next")

    // TLS registry — built-in HTTPS termination with runtime cert reload (ADR-0015).
    // Already pulled in transitively by the web layer above, but declared explicitly
    // so io.quarkus.tls.* (KeyStoreProvider, CertificateUpdatedEvent) resolves at
    // compile time, not just on the runtime classpath.
    implementation("io.quarkus:quarkus-tls-registry")

    // Persistence — Panache active record + JDBC
    implementation("io.quarkus:quarkus-hibernate-orm-panache")
    if (!sqliteOnly) {
        // PostgreSQL driver — omitted from the -Psqlite native build to save ~10-12 MB.
        // Include it for local dev / JVM runs so the datasource switching in
        // application.properties keeps working without extra config.
        implementation("io.quarkus:quarkus-jdbc-postgresql")
        // NOTE: a plain implementation("org.postgresql:postgresql:42.7.11") version
        // declaration here does NOT win — enforcedPlatform (below) silently pulls it
        // back down to the BOM's 42.7.8. The actual override lives in the
        // resolutionStrategy.eachDependency block, same mechanism as the Netty force.
    }
    implementation("io.quarkus:quarkus-flyway")

    // SQLite via xerial — not an official Quarkus extension; pulled in directly.
    // Native-image friction is the R-034 risk in ADR-0004; first thing to validate
    // when we run `gradle build -Dquarkus.package.type=native`.
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("org.hibernate.orm:hibernate-community-dialects")

    // Validation
    implementation("io.quarkus:quarkus-hibernate-validator")

    // Scheduling — for the activity poller (not used yet, but the dependency lives here)
    implementation("io.quarkus:quarkus-scheduler")

    // QR code rendering for peer creation. Only zxing-core (the BitMatrix encoder);
    // the PNG is written by QrService with java.util.zip — no AWT/ImageIO, so it
    // works in the GraalVM native image we ship. (Dropped zxing-javase, which pulled
    // in AWT and failed at runtime in native with "failed to encode QR".)
    implementation("com.google.zxing:core:3.5.3")

    // Test
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

// Force a few transitive dependencies to patched releases within the same line
// the Quarkus 3.33 LTS BOM already uses, so CVE fixes land without moving off
// the LTS line (see project_quarkus_lts_pin — we sit on LTS on purpose, and as
// of 2026-09-19 that means the 3.33 line, identifiable by its four-segment
// backport releases; 3.29 had none and was never LTS).
//
// enforcedPlatform (above) silently overrides any plain
// implementation("group:artifact:version") declaration back to the BOM's
// version, so these overrides MUST go through resolutionStrategy.eachDependency,
// not a version string on the dependency itself — this also covers transitive
// submodules that aren't declared directly (e.g. Netty's many codec modules).
//
// The 3.33.3.2 BOM already ships jackson 2.21.5, postgresql 42.7.13 and
// vertx-core 4.5.31 — every force we carried on 3.29.4 for those is now either
// equal to or older than the BOM, so they are gone rather than pinning us
// backwards.
configurations.all {
    resolutionStrategy.eachDependency {
        // tcnative artifacts live on their own 2.0.x line (the BOM pins 2.0.78.Final)
        // and have no 4.1.x release at all — a blanket io.netty force asks for a
        // netty-tcnative-classes:4.1.137.Final that does not exist and fails resolution.
        if (requested.group == "io.netty" && !requested.name.startsWith("netty-tcnative")) {
            // The BOM ships 4.1.136.Final. 4.1.137.Final fixes CVE-2026-59898 and
            // CVE-2026-59921 (netty-handler, netty-codec-http), which 4.1.136 does
            // not — hence one step past the BOM, staying inside 4.1.x.
            //
            // HISTORY, do not re-introduce blindly: on Quarkus 3.29.4 this was
            // CAPPED at 4.1.135.Final, because 4.1.136 changed the constructor of
            // io.netty.handler.ssl.ReferenceCountedOpenSslClientContext, which that
            // version's Vert.x GraalVM substitution called with the old signature —
            // the native build failed while the JVM test suite passed. The 3.33 BOM
            // ships 4.1.136 itself, so the substitution there expects the new
            // signature. Any change here needs a NATIVE build to verify; tests alone
            // cannot see this class of failure.
            useVersion("4.1.137.Final")
            because("CVE-2026-59898 (critical) and CVE-2026-59921, unfixed in the BOM's 4.1.136.Final; same 4.1.x line")
        }
    }
}
// NOT force-overridden: io.opentelemetry:opentelemetry-api (CVE-2026-45292,
// medium, unbounded memory in W3C baggage propagation, fixed in 1.62.0). The
// 3.33 BOM resolves 1.57.0 — closer than the 1.46.0 that 3.29.4 carried, but
// still five minors short, and the OTel API's compatibility with the
// Quarkus-managed OTel SDK/exporter at that distance needs real verification
// rather than a one-line force. Left as an open Dependabot alert (see #20).

group = "de.chriscohnen.islandr"
version = "1.0.0-rc.7"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    finalizedBy(tasks.jacocoTestReport)
}

// Jacoco's own plugin default only turns on the HTML report — codecov-action
// (ci.yml) reads build/reports/jacoco/test/jacocoTestReport.xml, which the
// default config never produces. Without this, the upload step silently found
// nothing to upload every run ("No coverage reports found"), which is why the
// Codecov badge never had real data despite the workflow step "succeeding".
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

// -- Native build hint --------------------------------------------------------
// Quarkus picks up `quarkus.native.*` from system properties or env.
//
// JVM build (dev):
//   ./gradlew quarkusDev
//
// Native build — SQLite only (CI / production, saves ~10-12 MB over JVM+PG):
//   ./gradlew build -Psqlite \
//                   -Dquarkus.native.enabled=true \
//                   -Dquarkus.native.container-build=true
//   The -Psqlite flag drops quarkus-jdbc-postgresql from the dependency tree.
//   -Dquarkus.native.container-build pulls a Mandrel container so no local
//   GraalVM is needed. Result: build/islandr-*-runner (Linux x86_64 binary).
//
// Native build — with PostgreSQL (when migrating off SQLite, see ADR-0004):
//   ./gradlew build -Dquarkus.native.enabled=true \
//                   -Dquarkus.native.container-build=true
//   (omit -Psqlite so the PG driver is included)
//
// SQLite (xerial) loads a native .so/.dylib at runtime — Quarkus knows how to
// register it for native-image via the JNI extension. If a fresh native build
// trips on Reflection/JNI/Resource access, the fix lives in
// src/main/resources/META-INF/native-image/ (config JSONs Quarkus generates
// most of automatically). Tracked as R-034 in ADR-0004.

// Native integration tests (src/native-test/java, run via `./gradlew testNative`):
// exercise the actual packaged native binary, not the JVM test suite. Guards the
// class of regression that JVM @QuarkusTest cannot see — e.g. a DTO missing from
// native-image reflection config, or a Response-wrapped entity that native's
// build-time serialization analysis can't see through (see NativeReflectionConfig
// and DiscoveryResource#startScan for the concrete incident this class of test
// closes — ADR-0014 slice 4 / rc.3–rc.6 / issue #25).
dependencies {
    "nativeTestImplementation"("io.quarkus:quarkus-junit5")
    "nativeTestImplementation"("io.rest-assured:rest-assured")
}

// The native binary boots under the prod profile (as shipped): no default admin
// password there (set one so the IT can log in — same as ci.yml's bash-based
// native smoke test), and its default datasource is a *relative* jdbc:sqlite:data/
// path that requires a pre-existing writable data/ directory. Unlike the Docker
// image smoke test (which deliberately keeps that default to catch a bad base
// image), this IT's only job is exercising native serialization — it needs a DB
// that just works, so point it at a scratch file like the bash smoke test does.
tasks.named<Test>("testNative") {
    environment("ISLANDR_ADMIN_PASSWORD", "native-it-pw")
    environment("QUARKUS_DATASOURCE_JDBC_URL", "jdbc:sqlite:${layout.buildDirectory.get()}/native-it.db")
    // islandr.discovery.mode defaults to "real" in prod (unlike wg/nft) — this IT
    // only exercises native serialization (see DiscoveryNativeIT), not an actual
    // network scan, and the sandboxed CI container has no route to any real host.
    environment("ISLANDR_DISCOVERY_MODE", "mock")
}
