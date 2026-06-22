plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

val keystoreProperties = Properties().apply {
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun readFujiNetRuntimeVersion(): String {
    val versionHeader = rootProject.file("tools/fujinet/work/fujinet-firmware/include/version.h")
    if (!versionHeader.isFile) {
        return "fujinet-runtime-v1"
    }
    val match = Regex("""#define\s+FN_VERSION_FULL\s+"([^"]+)"""")
        .find(versionHeader.readText())
    return match?.groupValues?.get(1) ?: "fujinet-runtime-v1"
}

val fujiNetRuntimeVersion = readFujiNetRuntimeVersion()

// openMSX core version is recorded by tools/openmsx/build-openmsx-core.sh in a
// .source-info stamp once the core has been staged (Phase 2). Until then, report
// the configured source branch from the staging script, or "Unknown".
fun readOpenMsxVersion(): String {
    val stamp = rootProject.file("app/src/main/cpp-generated/openmsx/.source-info")
    if (stamp.isFile) {
        val text = stamp.readText()
        val branch = Regex("""^source_branch=(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
        val commit = Regex("""^source_commit=(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()?.take(8)
        return when {
            !branch.isNullOrBlank() && !commit.isNullOrBlank() -> "$branch ($commit)"
            !commit.isNullOrBlank() -> commit
            !branch.isNullOrBlank() -> branch
            else -> "Unknown"
        }
    }
    return "Unknown"
}

val openMsxVersion = readOpenMsxVersion()

// Optional dev override: -PmsxAbi=arm64-v8a (or a comma list, e.g.
// -PmsxAbi=arm64-v8a,armeabi-v7a) builds a subset of ABIs for fast iteration.
// Unset => all four packaged ABIs (release/default).
val msxAbis: List<String>? = (project.findProperty("msxAbi") as String?)
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
val nativeAbiArgs: List<String> =
    if (msxAbis != null) msxAbis.flatMap { listOf("--abi", it) } else listOf("--all-abis")

// Stages + cross-compiles openMSX (and its 3rd-party stack) for the packaged
// Android ABIs from the local ~/Workspace/openMSX checkout, installing the static
// archives under src/main/cpp-generated/openmsx/install/<abi> for CMakeLists to
// link. This is the heavy Phase 2 step; it is registered here but intentionally
// NOT wired into preBuild yet so the Phase 1 scaffold builds the stub core. Wire
// the dependsOn() blocks below (mirroring prepareFujiNetRuntime) once the staging
// script produces archives.
val prepareOpenMsxCore by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Stages + cross-compiles openMSX for the packaged Android ABIs."
    workingDir = rootProject.projectDir
    commandLine(listOf("bash", rootProject.file("tools/openmsx/build-openmsx-core.sh").absolutePath) + nativeAbiArgs)
    inputs.file(rootProject.file("tools/openmsx/build-openmsx-core.sh"))
    outputs.dir(project.file("src/main/cpp-generated/openmsx"))
}

// Builds the FujiNet MSX Android runtime (libfujinet.so per ABI + runtime
// assets) from the local fujinet-pc-msx checkout. Up-to-date checked on the
// script/support inputs and the generated output dirs, so it only re-runs when
// the build inputs change.
val prepareFujiNetRuntime by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Builds the FujiNet MSX Android runtime for the packaged ABIs."
    workingDir = rootProject.projectDir
    commandLine(listOf("bash", rootProject.file("tools/fujinet/build-fujinet.sh").absolutePath) + nativeAbiArgs)
    inputs.file(rootProject.file("tools/fujinet/build-fujinet.sh"))
    inputs.dir(rootProject.file("tools/fujinet/support"))
    outputs.dir(project.file("src/main/assets-generated/fujinet"))
    outputs.dir(project.file("src/main/jniLibs-generated"))
}

tasks.configureEach {
    if (name.contains("Release") || name == "preBuild") {
        dependsOn(prepareFujiNetRuntime)
    }
}

tasks.matching { task ->
    task.name.startsWith("merge") && (
        task.name.endsWith("Assets")
            || task.name.endsWith("JniLibFolders")
            || task.name.endsWith("NativeLibs")
    )
}.configureEach {
    dependsOn(prepareFujiNetRuntime)
}

android {
    namespace = "online.fujinet.go.msx"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "online.fujinet.go.msx"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "OPENMSX_VERSION", "\"${openMsxVersion}\"")
        buildConfigField("String", "FUJINET_RUNTIME_VERSION", "\"${fujiNetRuntimeVersion}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
        ndk {
            if (msxAbis != null) {
                abiFilters += msxAbis
            } else {
                // x86 (32-bit) is intentionally excluded: no real x86-32 Android
                // devices exist, x86_64 covers emulators, and openMSX's libvorbis
                // dependency fails to cross-compile for it under clang
                // ('-mno-ieee-fp'). arm64-v8a (primary) + armeabi-v7a (legacy ARM)
                // + x86_64 (emulator) cover the live device matrix.
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    sourceSets {
        getByName("main") {
            // assets-generated/fujinet and jniLibs-generated/<abi>/libfujinet.so
            // are produced by tools/fujinet/build-fujinet.sh.
            assets.srcDir("src/main/assets-generated")
            jniLibs.srcDir("src/main/jniLibs-generated")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.lifecycle.runtime.testing)
    testImplementation(libs.androidx.lifecycle.viewmodel.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
