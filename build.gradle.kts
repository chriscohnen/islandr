plugins {
    java
    id("io.quarkus")
    jacoco
    id("com.github.jk1.dependency-license-report") version "3.1.5"
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
    implementation("io.vertx:vertx-auth-webauthn:4.5.35")

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
// The 3.33.4.1 BOM already ships Netty 4.1.139 (the former 4.1.137 force is
// gone: it would now pin us backwards), postgresql 42.7.13 and
// vertx-core 4.5.35 — every force we carried on 3.29.4 for those is now either
// equal to or older than the BOM, so they are gone rather than pinning us
// backwards.
configurations.all {
    resolutionStrategy.eachDependency {
        // The BOM ships Jackson below 2.21.7. 2.21.7 fixes five high-severity parser
        // DoS issues (number-pattern ReDoS, unbounded number parsing, quadratic
        // forward-reference completion, unbounded unknown type IDs) plus two
        // medium ones — reachable through every JSON request body, the login
        // included. All modules move together so no two disagree on a patch
        // level. jackson-annotations is left alone: since 2.20 it is versioned
        // "2.21", not "2.21.x", and has no 2.21.7.
        if (requested.group.startsWith("com.fasterxml.jackson")
                && requested.name != "jackson-annotations" && requested.name != "jackson-bom") {
            useVersion("2.21.7")
            because("five high and two medium jackson-core/-databind advisories fixed in 2.21.6/2.21.7; same 2.21 line")
        }
        // The BOM resolves OpenTelemetry 1.57.0; 1.62.0 fixes unbounded memory
        // allocation in W3C baggage propagation (medium). Islandr carries no
        // OTel SDK — only the API and its incubator, pulled in by the Quarkus
        // scheduler for optional tracing hooks, which without an SDK are
        // no-ops. So the API/SDK pairing that kept this un-forced before does
        // not arise. The incubator is alpha and must match the API exactly,
        // hence these four together; instrumentation-api and semconv keep
        // their own version lines. Verified by the native build and E2E.
        if (requested.group == "io.opentelemetry") {
            when (requested.name) {
                "opentelemetry-api", "opentelemetry-context", "opentelemetry-common" -> {
                    useVersion("1.62.0")
                    because("CVE-2026-45292, unbounded memory in W3C baggage propagation, fixed in 1.62.0")
                }
                "opentelemetry-api-incubator" -> {
                    useVersion("1.62.0-alpha")
                    because("must match opentelemetry-api exactly")
                }
            }
        }
    }
}
// OpenTelemetry was left un-forced until 1.2.0 for fear of an API/SDK
// mismatch; see the override above for why that does not apply here.

group = "de.chriscohnen.islandr"
version = "1.3.0-rc.4"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
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
//   CI pins the builder image to a dated tag (ci.yml, Mandrel 25.0.5.0 on
//   JDK 25) instead of the moving jdk-25 tag, so a release states which JDK
//   trust store it carries; bump the date deliberately.
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

// ---------------------------------------------------------------------------
// Third-party licence notices (ship-licence-notices). The native binary
// carries Quarkus, Netty, Vert.x, Jackson and more, mostly Apache-2.0, whose
// section 4 asks a redistributor to hand over the licence and the libraries'
// NOTICE texts. Generated, never hand-written — a hand-kept list goes stale at
// the next Quarkus bump.
//
// The plugin does not support the configuration cache, so it is NOT wired into
// the normal build: run `./gradlew thirdPartyNotices --no-configuration-cache`.
// It writes the committed file under META-INF/resources/licenses/, and CI
// regenerates it and fails on any difference or on a licence outside
// config/allowed-licenses.json.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    filters = arrayOf<com.github.jk1.license.filter.DependencyFilter>(
            com.github.jk1.license.filter.LicenseBundleNormalizer())
    renderers = arrayOf<com.github.jk1.license.render.ReportRenderer>(
            ThirdPartyNoticesRenderer(layout.buildDirectory.dir("reports/dependency-license").get().asFile))
    allowedLicensesFile = layout.projectDirectory.file("config/allowed-licenses.json").asFile
}

/**
 * One deterministic text file: every runtime library with its licence, then
 * each NOTICE text the libraries ship, then each distinct licence text once.
 * No timestamp and a fixed order, so CI can regenerate it and compare.
 */
class ThirdPartyNoticesRenderer(private val outDir: File) : com.github.jk1.license.render.ReportRenderer {
    override fun render(data: com.github.jk1.license.ProjectData) {
        val modules = data.allDependencies.sortedBy { "${it.group}:${it.name}:${it.version}" }
        fun licences(m: com.github.jk1.license.ModuleData): List<String> =
                com.github.jk1.license.render.LicenseDataCollector.multiModuleLicenseInfo(m).licenses
                        .mapNotNull { it.name }.distinct()
        fun embedded(m: com.github.jk1.license.ModuleData, notice: Boolean) =
                m.licenseFiles.flatMap { lf: com.github.jk1.license.LicenseFileData -> lf.fileDetails }
                        .filter { d: com.github.jk1.license.LicenseFileDetails -> File(d.file).name.uppercase().contains("NOTICE") == notice }
                        .map { d: com.github.jk1.license.LicenseFileDetails -> File(outDir, d.file) }
                        .filter { f: File -> f.isFile }
                        .sortedBy { f: File -> f.path }

        val sb = StringBuilder()
        sb.append("Third-party software in Islandr\n")
        sb.append("================================\n\n")
        sb.append("Islandr itself: Copyright (c) 2026 Christian Cohnen, licensed under the EUPL\n")
        sb.append("(see LICENSE and NOTICE). The libraries below are distributed with it under\n")
        sb.append("their own licences. Generated from the build's runtime dependencies.\n\n")
        sb.append("Libraries\n---------\n\n")
        for (m in modules) {
            sb.append("${m.group}:${m.name} ${m.version}\n")
            sb.append("  Licence: ${licences(m).joinToString(" / ").ifEmpty { "unknown" }}\n")
        }
        sb.append("\n\nNOTICE files shipped by the libraries\n-------------------------------------\n")
        for (m in modules) {
            for (f in embedded(m, notice = true)) {
                sb.append("\n=== ${m.group}:${m.name} ${m.version} — ${f.name}\n\n")
                sb.append(f.readText().trim()).append("\n")
            }
        }
        sb.append("\n\nLicence texts shipped by the libraries (each distinct text once)\n")
        sb.append("---------------------------------------------------------------\n")
        val seen = LinkedHashMap<String, MutableList<String>>()
        val texts = HashMap<String, String>()
        for (m in modules) {
            for (f in embedded(m, notice = false)) {
                val text = f.readText().trim()
                if (text.isEmpty()) continue
                val key = text.replace(Regex("\\s+"), " ")
                texts.putIfAbsent(key, text)
                seen.getOrPut(key) { mutableListOf() }.add("${m.group}:${m.name}")
            }
        }
        for ((key, users) in seen) {
            sb.append("\n=== Used by: ${users.distinct().joinToString(", ")}\n\n")
            sb.append(texts.getValue(key)).append("\n")
        }
        File(outDir, "THIRD-PARTY-NOTICES.txt").writeText(sb.toString())
    }
}

val thirdPartyNoticesFile = layout.projectDirectory.file(
        "src/main/resources/META-INF/resources/licenses/THIRD-PARTY-NOTICES.txt")

// Regenerate the committed notices. Needs --no-configuration-cache (see above).
tasks.register<Copy>("thirdPartyNotices") {
    group = "documentation"
    description = "Regenerates THIRD-PARTY-NOTICES.txt from the runtime dependencies and checks their licences"
    dependsOn("generateLicenseReport", "checkLicense")
    from(layout.buildDirectory.file("reports/dependency-license/THIRD-PARTY-NOTICES.txt"))
    into(thirdPartyNoticesFile.asFile.parentFile)
}
