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

    // Persistence — Panache active record + JDBC
    implementation("io.quarkus:quarkus-hibernate-orm-panache")
    if (!sqliteOnly) {
        // PostgreSQL driver — omitted from the -Psqlite native build to save ~10-12 MB.
        // Include it for local dev / JVM runs so the datasource switching in
        // application.properties keeps working without extra config.
        implementation("io.quarkus:quarkus-jdbc-postgresql")
        // Override BOM version (42.7.8) — CVE fix for SCRAM auth CPU exhaustion (patched 42.7.11)
        implementation("org.postgresql:postgresql:42.7.11")
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

// Force all Netty artifacts to a patched release. The Quarkus BOM (3.29.4)
// pulls Netty transitively at a version with several CVEs (DNS cache
// poisoning, memory exhaustion, IPv6 subnet-filter bypass). 4.1.135.Final is a
// patch bump within the same 4.1.x line the BOM already uses, so it stays
// compatible without moving off the pinned Quarkus version. The group-wide
// force overrides the enforcedPlatform constraints and also covers transitive
// Netty submodules that are not declared directly.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty") {
            useVersion("4.1.135.Final")
            because("CVE fixes patched in Netty 4.1.135.Final; same 4.1.x line as Quarkus 3.29 BOM")
        }
    }
}

group = "de.chriscohnen.islandr"
version = "0.12.1"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
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
}
