import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Machine-local, gitignored config (android/local.properties — the same file Android
// Studio writes sdk.dir into). Keeps the real backend domain out of the (public) repo:
// the phone/release build reads its BASE_URL from `homebase.baseUrl` here, or the
// HOMEBASE_BASE_URL env var, falling back to the example placeholder. See
// local.properties.example.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val backendBaseUrl: String = (localProperties.getProperty("homebase.baseUrl")
    ?: System.getenv("HOMEBASE_BASE_URL")
    ?: "https://your-dyndns-domain.example.com/api/v1/").trim()
// Escape backslash and double-quote so the value embeds safely as a Java string literal
// in the generated BuildConfig — a fat-fingered URL can't silently break compilation.
val backendBaseUrlLiteral: String = backendBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")

// Produktversion (#626). Single Source of Truth ist die Datei VERSION im Repo-Root — dieselbe,
// aus der Backend und Web ihre Version ziehen (rootProject ist hier android/). Fehlt sie, baut
// die App als 0.0.0-dev statt den Build zu brechen.
val appVersionName: String = rootProject.file("../VERSION").takeIf { it.isFile }
    ?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0.0-dev"

// versionCode muss bei jedem Update monoton wachsen, sonst verweigert Android die Installation
// („App not installed", ohne weitere Erklärung). Aus dem Semver abgeleitet:
// major*10000 + minor*100 + patch (1.1.0 → 10100). Damit reicht es, die VERSION-Datei zu pflegen;
// ein Vorab-Suffix (1.2.0-rc1) wird für den Code ignoriert — rc und Final teilen sich also einen
// Code, und minor/patch müssen unter 100 bleiben, sonst kollidieren 1.0.100 und 1.1.0.
//
// Ein unlesbares VERSION bricht hier bewusst den Build: ein stillschweigend zu kleiner Code fällt
// erst auf dem Handy auf, und dann ohne Hinweis auf die Ursache. Der Fallback 0.0.0-dev (fehlende
// Datei) ergäbe Code 0, den AGP ablehnt — deshalb die untere Schranke 1.
val appVersionCode: Int = run {
    val parts = appVersionName.substringBefore('-').split('.').map {
        it.toIntOrNull() ?: error("VERSION ist kein Semver: '$appVersionName' (erwartet major.minor.patch)")
    }
    require(parts.size == 3) { "VERSION ist kein Semver: '$appVersionName' (erwartet major.minor.patch)" }
    (parts[0] * 10000 + parts[1] * 100 + parts[2]).coerceAtLeast(1)
}

// Kurz-SHA des Builds, rein informativ (in den Einstellungen unter „Über" sichtbar). Leer, wenn
// ohne Git-Kontext gebaut wird.
val gitSha: String = (System.getenv("GIT_SHA")?.trim()?.takeIf { it.isNotEmpty() }
    ?: runCatching {
        providers.exec { commandLine("git", "rev-parse", "HEAD") }
            .standardOutput.asText.get().trim()
    }.getOrNull().orEmpty()).take(7)

android {
    namespace = "com.homebase.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.homebase.android"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default — also the value the release/phone build inherits: the real backend from
        // local.properties / HOMEBASE_BASE_URL, else the example placeholder. Debug overrides
        // it to the emulator loopback below.
        buildConfigField("String", "BASE_URL", "\"$backendBaseUrlLiteral\"")
        // Commit dieses Builds (#626) — neben VERSION_NAME in den Einstellungen sichtbar.
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080/api/v1/\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-sign the release so the sideloaded APK is actually installable on a phone
            // — an unsigned release APK fails with "couldn't be installed … issue with the app".
            // Sufficient for a private, hand-distributed 2-person app; a dedicated upload
            // keystore would be the upgrade if these ever need a stable cross-machine signing
            // identity or store distribution.
            signingConfig = signingConfigs.getByName("debug")
            // BASE_URL inherited from defaultConfig (backendBaseUrl) — the real domain.
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest + Android resources on the unit-test classpath
            // (it boots a sandboxed Android runtime). Required by LogoutTeardownComposeTest, which
            // drives the real MainActivity auth-state→ViewModelStore.clear() effect over a Compose
            // composition without an emulator.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    // AppCompat backports per-app locales (AppCompatDelegate.setApplicationLocales) to
    // minSdk 26, even though MainActivity is a ComponentActivity — used by the in-app
    // language switcher (de/en). The androidx.appcompat AppLocalesMetadataHolderService in
    // the manifest persists the choice across restarts (autoStoreLocales=true).
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    // Local reminder notifications (#429 Phase 2c): one delayed one-shot per timed todo.
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.kotlin.codegen)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric + Compose UI test: run a real composition (createComposeRule) under a sandboxed
    // Android runtime as a plain JVM unit test, no emulator — drives MainActivity's logout-teardown
    // effect (issue #192). ui-test-manifest supplies the ComponentActivity host the rule needs.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ── Randomised-order unit-test job (issue #363) ───────────────────────────────────────────────
// `testDebugUnitTest` runs the classes in a stable, deterministic order. That hides order-dependent
// cross-test pollution: PR #359 fixed a leak in one test that only failed a *later*, unrelated test,
// and main went red only because a timing shift reordered them. This task reruns the SAME compiled
// unit tests in ONE JVM but in a RANDOMISED class order (driven by `RandomOrderSuite`, which logs the
// seed so a failure is reproducible via -Dhomebase.testOrderSeed=<n>). It is a *separate* task — the
// normal suite stays deterministic and is never made flaky by this. CI runs it as its own job.
//
// Implementation: AGP owns `testDebugUnitTest`; we register a sibling Test task that reuses its
// classpath + compiled test classes (so no recompile/second toolchain), but points the runner at the
// single `RandomOrderSuite` entry class and forces a single fork. The suite itself walks the compiled
// test-classes dir (handed in via `homebase.testClassesDir`) to discover every *Test class.
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) {
    // Defer until AGP has registered testDebugUnitTest so we can clone its wiring.
    afterEvaluate {
        val unitTest = tasks.named<Test>("testDebugUnitTest").get()

        // RandomOrderSuite is an *aggregator* entry point, not a normal test: run on its own it
        // discovers and reruns every other *Test class, and it needs the homebase.testClassesDir
        // system property the dedicated task supplies. Exclude it from the standard unit-test tasks
        // (which would otherwise pick it up by the *Test naming convention and fail with an
        // initializationError), so they keep running each class exactly once in their normal
        // deterministic order. We exclude only on the AGP-owned tasks — NOT via a blanket
        // tasks.withType<Test> — because in Gradle's TestFilter an exclude pattern beats an include
        // for the same class, which would gut the dedicated task's lone entry point below.
        listOf("testDebugUnitTest", "testReleaseUnitTest").forEach { name ->
            tasks.matching { it.name == name }.configureEach {
                (this as Test).filter {
                    excludeTestsMatching("com.homebase.android.testutil.RandomOrderSuite")
                }
            }
        }

        tasks.register<Test>("testRandomOrderUnitTest") {
            group = "verification"
            description =
                "Runs the debug unit tests in a single JVM in randomised class order to surface " +
                    "order-dependent cross-test pollution (issue #363). Seed is logged for replay."

            // Reuse the real unit-test task's classpath + compiled classes — identical environment,
            // no second compile or SDK setup.
            testClassesDirs = unitTest.testClassesDirs
            classpath = unitTest.classpath
            // AGP configures the unit-test JVM (bootclasspath, android resources, system properties
            // for Robolectric, etc.) on testDebugUnitTest; carry those over so the randomised run is a
            // faithful clone and not subtly different.
            jvmArgs = unitTest.jvmArgs
            systemProperties(unitTest.systemProperties)
            unitTest.dependsOn.forEach { dependsOn(it) }

            useJUnit()
            filter {
                // Drive ONLY the suite entry class — JUnit (not Gradle) then runs the discovered
                // classes in the randomised order the suite computes.
                includeTestsMatching("com.homebase.android.testutil.RandomOrderSuite")
            }

            // Single JVM is the whole point: cross-class pollution only shows when classes share a
            // process. Never reuse a worker across "rounds" and never parallelise.
            maxParallelForks = 1
            forkEvery = 0
            // Surface each class as it runs and let the suite's stdout seed line through, so a CI
            // failure shows the order and the replay seed.
            testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }

            // Tell the suite where the compiled test classes live so it can enumerate *Test classes
            // without a classpath-scanning dependency. Allow pinning the shuffle seed for replay.
            doFirst {
                // testClassesDirs can hold several roots (Kotlin + Java output); hand them all over,
                // joined with the platform path separator, for the suite to enumerate.
                systemProperty(
                    "homebase.testClassesDir",
                    unitTest.testClassesDirs.files.joinToString(File.pathSeparator) { it.absolutePath },
                )
                (project.findProperty("testOrderSeed") as String?)?.let {
                    systemProperty("homebase.testOrderSeed", it)
                }
            }
        }
    }
}
