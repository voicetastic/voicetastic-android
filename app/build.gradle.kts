import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

// -----------------------------------------------------------------------------
// Rust bridge configuration
// -----------------------------------------------------------------------------
//
// The voice protocol implementation lives in the desktop crate
// `voicetastic-core` (pulled in via the `third_party/voicetastic-desktop`
// git submodule). The companion bridge crate
// `crates/voicetastic-android-bridge` exposes that protocol to Kotlin via
// UniFFI.
//
// At build time we:
//   1. `cargo build --release --target <abi>` each enabled Android ABI,
//      producing `libvoicetastic.so` per architecture.
//   2. Copy each `.so` into `app/src/main/jniLibs/<abi>/`.
//   3. Run the bridge's `uniffi-bindgen` binary to emit
//      `app/build/generated/source/uniffi/.../voicetastic.kt`.
//   4. Add the generated dir as a Kotlin source set so the Android
//      compile step picks it up.
//
// Tasks: `cargoBuildAll`, `uniffiBindgen`. Both are wired into
// `preBuild` so a plain `./gradlew :app:assembleDebug` produces a
// working APK from a clean checkout (assuming Rust + NDK are installed).

val rustRoot = file("$rootDir/third_party/voicetastic-desktop")
val rustBridgeCrate = "voicetastic-android-bridge"
// Must match `[lib] name = "..."` in `voicetastic-android-bridge/Cargo.toml`.
// Kept equal to the UDL namespace (`voicetastic`) so the Rust-side
// UniFFI scaffolding emits a single, consistent symbol prefix across
// both `fn_` exports and the `checksum_` sanity-check stubs.
// The Kotlin loader is told to look for `libvoicetastic.so` (rather
// than the default `libuniffi_voicetastic.so`) via the bridge crate's
// `uniffi.toml` -> `[bindings.kotlin] cdylib_name = "voicetastic"`.
val rustCdylibName = "voicetastic"
val rustUdl = file("$rustRoot/crates/$rustBridgeCrate/src/voicetastic.udl")
val rustTargetDir = file("$rustRoot/target-android")
val generatedUniffiDir = layout.buildDirectory.dir("generated/source/uniffi")

/**
 * ABIs to build. Each entry maps Android ABI name -> Rust target triple.
 * Keep `arm64-v8a` (real devices) and `x86_64` (most emulators) on by
 * default; the other two are commented out for faster CI cycles -- enable
 * them for release builds.
 */
val rustAbis: Map<String, String> = mapOf(
    "arm64-v8a"     to "aarch64-linux-android",
    "x86_64"        to "x86_64-linux-android",
    // "armeabi-v7a"   to "armv7-linux-androideabi",
    // "x86"           to "i686-linux-android",
)

/** Android API level the .so links against. Must be >= `android.defaultConfig.minSdk`. */
val rustAndroidApiLevel = 29

/**
 * Resolve the NDK toolchain bin dir. Order:
 *   1. `ANDROID_NDK_HOME` env var (CI-friendly).
 *   2. `ndk.dir` in `local.properties`.
 *   3. `<sdk.dir>/ndk/<latest>` discovered automatically.
 */
fun ndkToolchainBin(): File {
    val envNdk = System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { file(it) }
    val localProps = Properties().apply {
        val f = file("$rootDir/local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val propNdk: File? = localProps.getProperty("ndk.dir")?.let { file(it) }
    val sdkDir: File? = localProps.getProperty("sdk.dir")?.let { file(it) }
    val autoNdk: File? = sdkDir?.resolve("ndk")
        ?.listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name } // pick the highest-numbered installed NDK

    val ndk = envNdk ?: propNdk ?: autoNdk
        ?: throw GradleException(
            "No Android NDK found. Set ANDROID_NDK_HOME, `ndk.dir` in local.properties, " +
                "or install one via the Android Studio SDK Manager."
        )
    val bin = ndk.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
    if (!bin.isDirectory) {
        throw GradleException(
            "NDK toolchain not found at $bin (NDK root: $ndk). " +
                "Reinstall the NDK or set ANDROID_NDK_HOME to a valid path."
        )
    }
    return bin
}

/** clang wrapper for a given Rust triple, e.g. `aarch64-linux-android24-clang`. */
fun ndkClang(target: String): File {
    // Rust's `armv7-linux-androideabi` triple maps to NDK's
    // `armv7a-linux-androideabi24-clang` (note the `a` suffix).
    val ndkTriple = if (target == "armv7-linux-androideabi") "armv7a-linux-androideabi" else target
    return ndkToolchainBin().resolve("$ndkTriple$rustAndroidApiLevel-clang")
}

/**
 * Register one `cargoBuild<Abi>` task per enabled ABI plus an aggregate
 * `cargoBuildAll`. Each builds the bridge crate for its Rust target with
 * the matching NDK clang as linker.
 */
val cargoBuildTasks = rustAbis.map { (abi, target) ->
    val capAbi = abi.split("-", "_").joinToString("") { it.replaceFirstChar(Char::uppercase) }
    tasks.register<Exec>("cargoBuild$capAbi") {
        group = "rust"
        description = "Build the voicetastic-core UniFFI bridge for Android $abi ($target)."
        workingDir = rustRoot
        // Up-to-date check: source files under the bridge crate + the udl.
        inputs.files(fileTree("$rustRoot/crates/$rustBridgeCrate") {
            exclude("target/**", "target-android/**")
        })
        inputs.files(fileTree("$rustRoot/crates/voicetastic-core") {
            exclude("target/**", "target-android/**")
        })
        inputs.file(rustUdl)
        val outSo = file("$rustTargetDir/$target/release/lib$rustCdylibName.so")
        outputs.file(outSo)

        commandLine(
            "cargo", "build",
            "-p", rustBridgeCrate,
            "--release",
            "--target", target,
            "--target-dir", rustTargetDir.absolutePath,
        )
        doFirst {
            val clang = ndkClang(target)
            if (!clang.isFile) {
                throw GradleException(
                    "Missing NDK clang for $target at $clang. " +
                        "Either the NDK r$rustAndroidApiLevel toolchain isn't installed for this ABI, " +
                        "or the NDK version doesn't ship API $rustAndroidApiLevel; pick a lower level."
                )
            }
            // Cargo env-var convention: `CARGO_TARGET_<TRIPLE_UPPERCASED>_LINKER`.
            // The triple needs `-` and `.` replaced by `_`.
            val envKey = "CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER"
            environment(envKey, clang.absolutePath)
            // Some sys crates (ring, openssl-sys, ...) read these too.
            environment("CC_$target", clang.absolutePath)
            environment("AR_$target", ndkToolchainBin().resolve("llvm-ar").absolutePath)
        }
        // After the .so lands, copy it into jniLibs so the Android build
        // packages it. We do this as a doLast rather than a separate
        // Copy task to keep up-to-date semantics simple.
        doLast {
            val dest = file("$projectDir/src/main/jniLibs/$abi/lib$rustCdylibName.so")
            dest.parentFile.mkdirs()
            outSo.copyTo(dest, overwrite = true)
        }
    }
}

val cargoBuildAll = tasks.register("cargoBuildAll") {
    group = "rust"
    description = "Build libvoicetastic.so for every enabled Android ABI."
    dependsOn(cargoBuildTasks)
}

/**
 * Run the bridge's `uniffi-bindgen` binary to emit the Kotlin wrapper.
 *
 * UniFFI's standalone bindgen entry point is re-exported as a bin from
 * `voicetastic-android-bridge` (see `src/bin/uniffi-bindgen.rs` there)
 * so we don't need to `cargo install` anything separately.
 */
val uniffiBindgen = tasks.register<Exec>("uniffiBindgen") {
    group = "rust"
    description = "Generate Kotlin bindings from the bridge crate's UDL file."
    workingDir = rustRoot
    inputs.file(rustUdl)
    inputs.files(fileTree("$rustRoot/crates/$rustBridgeCrate/src") { include("**/*.rs") })
    outputs.dir(generatedUniffiDir)

    val outDir = generatedUniffiDir.get().asFile
    val uniffiToml = file("$rustRoot/crates/$rustBridgeCrate/uniffi.toml")
    inputs.file(uniffiToml)
    commandLine(
        "cargo", "run",
        "-p", rustBridgeCrate,
        "--bin", "uniffi-bindgen",
        "--features", "uniffi/cli",
        "--",
        "generate",
        rustUdl.absolutePath,
        "--config", uniffiToml.absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
        "--no-format", // ktlint isn't on PATH in CI; we don't depend on it
    )
    doFirst { outDir.mkdirs() }
}

android {
    namespace = "re.chasam.voicetastic"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "re.chasam.voicetastic"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // PR3 default: runtime mesh traffic is driven by the Rust bridge.
        buildConfigField("boolean", "USE_RUST_MESH_SERVICE", "true")

        // Only package the ABIs we actually cross-compile for. Without
        // this, the AGP packager will refuse the build because
        // `jniLibs.srcDir` is non-empty but some declared ABIs are
        // missing a .so.
        ndk {
            abiFilters += rustAbis.keys
        }
    }

    // Wire the generated UniFFI Kotlin into the main source set so the
    // Kotlin compile step picks it up. Keep the directory layout
    // mirroring what the UniFFI generator emits (`uniffi/voicetastic/...`).
    sourceSets {
        getByName("main") {
            kotlin.srcDirs(generatedUniffiDir.get().asFile)
        }
    }

    signingConfigs {
        // Picked up from CI env vars (see .gitlab-ci.yml `assemble-release`).
        // When unset (e.g. local debug builds), the release APK stays unsigned.
        val storeFileEnv = System.getenv("RELEASE_STORE_FILE")
        if (!storeFileEnv.isNullOrBlank() && file(storeFileEnv).exists()) {
            create("release") {
                storeFile = file(storeFileEnv)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Needed for `BuildConfig.USE_RUST_MESH_SERVICE` (AGP 8+ default
        // is `false`, which would drop the generated `BuildConfig`
        // class and break compilation of `RustMeshSession`'s callers in
        // PR 3).
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
            }
        }
    }
}

// Wire the Rust/UniFFI tasks into the Android build graph.
afterEvaluate {
    // Make sure the generated Kotlin exists before any Kotlin compile.
    tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
        .configureEach { dependsOn(uniffiBindgen) }
    // Make sure the .so files exist before the merger packs jniLibs.
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
        .configureEach { dependsOn(cargoBuildAll) }

    // Exclude test configs from protobuf extraction (existing behaviour).
    tasks.matching { it.name.contains("extractInclude") && it.name.contains("UnitTest") }.configureEach {
        enabled = false
    }
    tasks.matching { it.name.contains("extractProto") && it.name.contains("UnitTest") }.configureEach {
        enabled = false
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    // Core
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Protobuf
    implementation(libs.protobuf.javalite)

    // USB serial (Meshtastic over USB host)
    implementation(libs.usb.serial)

    // UniFFI Kotlin bindings runtime (loads libvoicetastic.so via JNA).
    implementation(libs.jna) { artifact { type = "aar" } }

    // Unit tests (Kotest + Cucumber)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)

    // Instrumented tests
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}


