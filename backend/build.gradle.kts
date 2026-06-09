plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("io.ktor.plugin") version "3.1.3"
    application
}

group = "com.homebase"
version = "1.0.0"

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

    // Server-side PDF generation for the recipe export (issue #136). Pure-JVM, no
    // native deps; uses java.desktop (AWT Color), present in the temurin JRE image.
    implementation("com.github.librepdf:openpdf:1.3.43")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-websockets:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.0")
    testImplementation("com.h2database:h2:2.2.224")
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

tasks.test {
    // bcrypt at the production cost (12) would dominate the suite — every test that
    // seeds users hashes passwords. Drop the work factor to bcrypt's minimum for tests
    // only; production never sets this property and keeps the default cost of 12.
    systemProperty("homebase.bcrypt.cost", "4")
}
