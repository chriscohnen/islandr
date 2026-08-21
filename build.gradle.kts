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

// Force several transitive dependencies to patched releases within the same
// minor line the Quarkus 3.29.4 BOM already uses, so CVE fixes land without
// moving off the pinned Quarkus version (see project_quarkus_lts_pin — we stay
// on LTS on purpose). enforcedPlatform (above) silently overrides any plain
// implementation("group:artifact:version") declaration back to the BOM's
// version, so these overrides MUST go through resolutionStrategy.eachDependency,
// not a version string on the dependency itself — this also covers transitive
// submodules that aren't declared directly (e.g. Netty's many codec modules).
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty") {
            // CAPPED AT 4.1.135.Final — do not bump further without re-running a native
            // build. Netty 4.1.136.Final changes the constructor signature of
            // io.netty.handler.ssl.ReferenceCountedOpenSslClientContext, which Quarkus
            // 3.29.4's Vert.x GraalVM substitution (VertxSubstitutions.java,
            // Target_DefaultSslContextFactory) calls directly at native-image build
            // time with the old signature — native build fails with "Discovered
            // unresolved method during parsing" even though the JVM test suite passes
            // fine (substitutions only run in the native codepath). CVEs fixed in
            // 4.1.136.Final (CVE-2026-59898/59899/59900/59901/59919/59921/56745/56746/
            // 55831/55833/55851) are therefore NOT fixable via a same-line version bump
            // on this Quarkus version — needs either a Quarkus upgrade or a Quarkus-side
            // fix to the substitution, tracked as an open Dependabot alert.
            useVersion("4.1.135.Final")
            because("4.1.136.Final breaks the native build (Quarkus 3.29.4 Vert.x GraalVM substitution incompatibility) — capped here pending a Quarkus upgrade")
        }
        if (requested.group == "org.postgresql" && requested.name == "postgresql") {
            useVersion("42.7.12")
            because("CVE-2026-42198 (SCRAM auth CPU exhaustion) and CVE-2026-54291 (channel-binding downgrade); same 42.7.x line as Quarkus 3.29 BOM")
        }
        if (requested.group == "com.fasterxml.jackson.core" &&
            (requested.name == "jackson-core" || requested.name == "jackson-databind")) {
            useVersion("2.21.5")
            because("Multiple CVEs in jackson-databind/jackson-core (CVE-2026-54512/54513/54514/54515/59888 + GHSA-72hv-8253-57qq incomplete-fix follow-up); same 2.x line as Quarkus 3.29 BOM")
        }
        if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
            // jackson-annotations only ships minor-numbered releases (2.21, not 2.21.5) —
            // keep it aligned with the 2.21.x core/databind force above without a patch suffix.
            useVersion("2.21")
            because("Keep jackson-annotations in lockstep with the jackson-core/jackson-databind 2.21.x force above")
        }
        if (requested.group == "io.vertx" && requested.name == "vertx-core") {
            useVersion("4.5.27")
            because("CVE-2026-1002 (static handler cache DoS) and CVE-2026-6860 (unbounded SNI SslContext cache growth); same 4.5.x line as Quarkus 3.29 BOM")
        }
    }
}
// NOT force-overridden: io.opentelemetry:opentelemetry-api (CVE-2026-45292,
// medium, unbounded memory in W3C baggage propagation, fixed in 1.62.0). The
// BOM currently resolves 1.46.0 — a 16-minor-version jump is too large to
// treat as a same-line patch bump like the others above; the OTel API's
// compatibility with the Quarkus-managed OTel SDK/exporter at that distance
// needs real verification, not a one-line force. Left as an open Dependabot
// alert pending a dedicated look (see #20).

group = "de.chriscohnen.islandr"
version = "0.17.1"

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
