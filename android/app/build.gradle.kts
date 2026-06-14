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

android {
    namespace = "com.homebase.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.homebase.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default — also the value the release/phone build inherits: the real backend from
        // local.properties / HOMEBASE_BASE_URL, else the example placeholder. Debug overrides
        // it to the emulator loopback below.
        buildConfigField("String", "BASE_URL", "\"$backendBaseUrl\"")
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
