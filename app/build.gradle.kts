plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// VERSION (MAJOR.MINOR.PATCH[-PRERELEASE]) is the single source of truth; the release workflow checks it against the tag.
val appVersion: String = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.substringBefore('-').split(".").map { it.toInt() }
require(versionParts.size == 3) { "VERSION must be MAJOR.MINOR.PATCH, got '$appVersion'" }
// The common commit a release was built with (scripts/release.ps1 writes it).
val commonRef: String = rootProject.file("COMMON_REF").takeIf { it.exists() }?.readText()?.trim().orEmpty()
// GitHub repository the in-app updater checks (owner/name); the release workflow sets it.
val updateRepo: String = System.getenv("NPW_UPDATE_REPO")?.takeIf { it.isNotBlank() } ?: "example/NyaPassword-android"

// The NDK both AGP (stripping) and cargo-ndk (building the Rust core) use.
val ndkVersionPinned = "28.2.13676358"

android {
    namespace = "app.nya.password"
    compileSdk = 36
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "app.nya.password"
        minSdk = 26
        targetSdk = 36
        versionName = appVersion
        versionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "COMMON_REF", "\"$commonRef\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing comes from the environment (the release workflow restores the keystore from a secret).
    // Without it `assembleRelease` still works and produces an unsigned APK, which must never be published.
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (!keystoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))
    // Kotlin bindings of the Rust core, generated from the built library (see uniffiBindings).
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/uniffi/kotlin"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
        // generated UniFFI bindings
        checkGeneratedSources = false
    }

    packaging {
        jniLibs {
            // JNA ships its own libjnidispatch.so per ABI; keep only the ABIs the core is built for.
            excludes += listOf("**/armeabi-v7a/**", "**/x86/**")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// The Rust core (../Cargo.toml, needs ../common next to this repo) as
// libnpw_android.so for each ABI, via cargo-ndk. Always built optimized:
// Argon2 (unlocking) is unusably slow in a debug build. Cargo itself skips unchanged work.
val rustAbis = listOf("arm64-v8a", "x86_64")
val rustLibs = layout.buildDirectory.dir("rustJniLibs")
val cargo = System.getenv("CARGO") ?: "cargo"
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Build the Rust core for Android with cargo-ndk"
    val out = rustLibs.get().asFile
    val ndkDir = androidComponents.sdkComponents.ndkDirectory
    workingDir = rootProject.projectDir
    doNotTrackState("cargo tracks its own inputs")
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
    }
    commandLine(
        listOf(cargo, "ndk") +
            rustAbis.flatMap { listOf("-t", it) } +
            listOf("--platform", "26", "-o", out.absolutePath, "build", "--release", "--lib"),
    )
}

// Kotlin bindings (UniFFI library mode) from the arm64 library: the interface
// is the same for every ABI. uniffi-bindgen/ is a host tool in the same cargo
// workspace, so its uniffi version always matches the core's.
val uniffiOut = layout.buildDirectory.dir("generated/uniffi/kotlin")
val uniffiBindings = tasks.register<Exec>("uniffiBindings") {
    group = "build"
    description = "Generate the Kotlin bindings of the Rust core"
    dependsOn(cargoBuild)
    val out = uniffiOut.get().asFile
    val lib = rustLibs.get().file("arm64-v8a/libnpw_android.so").asFile
    workingDir = rootProject.projectDir
    doNotTrackState("derived from the library cargo just built")
    doFirst { out.deleteRecursively() }
    commandLine(
        cargo, "run", "--quiet", "-p", "uniffi-bindgen", "--",
        "generate", "--library", lib.absolutePath, "--language", "kotlin", "--out-dir", out.absolutePath, "--no-format",
    )
}
tasks.named("preBuild") { dependsOn(uniffiBindings) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.biometric)
    // Credential Manager provider APIs (passwords and passkeys, Android 14+). No Play services.
    implementation(libs.androidx.credentials)
    // Inline (keyboard) autofill suggestions, Android 11+.
    implementation(libs.androidx.autofill)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // UniFFI's Kotlin bindings call the core through JNA.
    implementation("${libs.jna.get()}@aar")
    // Events WebSocket.
    implementation(libs.okhttp)
    // Emergency Kit QR codes: zxing in-app, no Google Play services needed.
    implementation(libs.zxing.android.embedded)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    // Desktop JNA (with its native dispatch library) for HostBindingsTest, which loads the core built for the host.
    testImplementation(libs.jna)
}
