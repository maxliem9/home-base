plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("io.ktor.plugin") version "3.1.3"
    application
}

group = "com.homebase"

// Produktversion (#626). Single Source of Truth ist die Datei `VERSION` im Repo-Root — sie gilt
// für Backend, Web und Android gemeinsam. Im Docker-Build liegt der Repo-Root nicht im
// Build-Context (`context: ./backend`), deshalb reicht CI Version und Commit als Build-Args
// via APP_VERSION/GIT_SHA durch; die env-Variablen haben darum Vorrang vor der Datei.
val appVersion: String = System.getenv("APP_VERSION")?.trim()?.takeIf { it.isNotEmpty() }
    ?: file("../VERSION").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    ?: "0.0.0-dev"

// Kurz-SHA des Builds — aus dem CI-Build-Arg, sonst aus dem lokalen Checkout, sonst leer
// (z. B. Build aus einem Tarball ohne .git). Rein informativ, nie build-entscheidend.
val gitSha: String = (System.getenv("GIT_SHA")?.trim()?.takeIf { it.isNotEmpty() }
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "HEAD") }
            .standardOutput.asText.get().trim()
    }.getOrNull().orEmpty()).take(7)

version = appVersion

application {
    mainClass.set("com.homebase.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.3"
val exposedVersion = "0.61.0"
val flywayVersion = "10.11.0"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")

    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")

    implementation("ch.qos.logback:logback-classic:1.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("at.favre.lib:bcrypt:0.10.2")

    // Web Push (VAPID) delivery for todo reminders (#429 Phase 2b). Dormant unless VAPID keys
    // are set. web-push declares BouncyCastle as optional in its POM, so we add it explicitly —
    // it is the JCE provider the library needs for the ECDH/VAPID crypto and must be on the
    // runtime classpath (otherwise PushService fails when VAPID is configured).
    implementation("nl.martijndwars:web-push:5.1.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Server-side PDF generation for the recipe export (issue #136). Pure-JVM, no
    // native deps; uses java.desktop (AWT Color), present in the temurin JRE image.
    implementation("com.github.librepdf:openpdf:1.3.43")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.0")
    // H2 bleibt nur noch für die isolierten Service-/Logik-Unit-Tests (TodoServiceTest,
    // TimeServiceTest … — je eine minimale SchemaUtils-Schema). Die ROUTEN-Tests laufen über
    // TestDatabase gegen echtes Postgres (#555), damit die Prod-Handler nicht mehr gegen ein von
    // H2 abweichendes Schema getestet werden (Hand-Kaskaden, CHECKs, REPEATABLE_READ).
    testImplementation("com.h2database:h2:2.2.224")
    // Testcontainers-Postgres für die Routen-Test-Suite (#555). Ein geteilter Container für die
    // ganze Suite (siehe TestDatabase) — braucht eine laufende Docker-Engine lokal wie in CI.
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("io.mockk:mockk:1.13.10")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Pin Kotlin's bytecode target to match Java above. Without this, Kotlin infers
// the target from the JDK running Gradle (e.g. 24 in CI), which then disagrees
// with the Java tasks' 21 and fails the JVM-target consistency check.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

ktor {
    fatJar {
        archiveFileName.set("homebase-backend.jar")
    }
}

// Merge colliding META-INF/services/* files when building the fat jar. Flyway 10
// discovers its SQL migration resolver/parser via the SPI file
// META-INF/services/org.flywaydb.core.extensibility.Plugin, and BOTH flyway-core
// (20 plugins, incl. the SQL resolver) and flyway-database-postgresql (the Postgres
// dialect) ship their own copy. Shadow's default is last-wins, which dropped
// flyway-core's entry — so the packaged jar had no SQL resolver, Flyway recognised
// none of the V*.sql files ("did not follow the filename convention" / "No migrations
// found"), the schema stayed empty, and the user seeder crash-looped the app on a
// missing "users" table. Concatenating the service files keeps every plugin. CI guards
// against a regression by booting the built fat jar against a fresh Postgres (issue #9,
// the "Smoke-test fat-jar migrations" step in .github/workflows/ci.yml).
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    mergeServiceFiles()
}

// Version + Commit als Ressource in den Jar backen (#626), damit `AppVersion` sie zur Laufzeit
// lesen kann — auch aus dem Fat-Jar heraus, wo weder die VERSION-Datei noch .git existieren.
val generateVersionProperties by tasks.registering {
    val outFile = layout.buildDirectory.file("generated/version/version.properties")
    // Lokale Kopien: die Task-Action darf keine Script-Properties einfangen, sonst ist der Task
    // nicht configuration-cache-fähig ("cannot serialize Gradle script object references").
    val version = appVersion
    val commit = gitSha
    // Als Task-Inputs deklariert, damit ein Versions-/Commit-Wechsel die Ressource neu schreibt
    // (und ein reiner Rebuild sie up-to-date lässt).
    inputs.property("version", version)
    inputs.property("commit", commit)
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText("version=$version\ncommit=$commit\n")
    }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/version"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateVersionProperties)
}

tasks.test {
    // bcrypt at the production cost (12) would dominate the suite — every test that
    // seeds users hashes passwords. Drop the work factor to bcrypt's minimum for tests
    // only; production never sets this property and keeps the default cost of 12.
    systemProperty("homebase.bcrypt.cost", "4")
    // The default forked test JVM heap (Gradle picks a small fraction of RAM on CI
    // runners) OOMs in WebPushNotifierTest's ECDH/HKDF key-agreement work (issue #572);
    // give the test JVM a fixed, generous heap so the suite is deterministic across
    // runners rather than dependent on the host's memory pressure.
    maxHeapSize = "2g"
}
