plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version comes from the v* tag being released, so an installed app always sees
// a higher versionCode than the one it replaces. Local builds fall back to
// 0.0.0-dev / code 1, which no published release can be confused with.
val releaseTag: String? = (project.findProperty("droidrunner.releaseTag") as String?)
    ?: System.getenv("DROIDRUNNER_RELEASE_TAG")
val hasReleaseKey = System.getenv("ANDROID_KEYSTORE_FILE") != null
val semver: String? = releaseTag?.removePrefix("v")
    ?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }

android {
    namespace = "io.github.m96chan.droidrunner"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.m96chan.droidrunner"
        minSdk = 28
        targetSdk = 35
        versionCode = semver?.split(".")?.let { (major, minor, patch) ->
            major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
        } ?: 1
        versionName = semver ?: "0.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Public identifiers of the DroidRunner GitHub App used for device-flow login.
        // Self-builders register their own app and set these in gradle.properties.
        buildConfigField("String", "GITHUB_APP_CLIENT_ID", "\"${project.findProperty("droidrunner.githubAppClientId") ?: ""}\"")
        buildConfigField("String", "GITHUB_APP_SLUG", "\"${project.findProperty("droidrunner.githubAppSlug") ?: ""}\"")
        // Public keys trusted to sign runtime manifests, comma-separated
        // X.509/base64. Empty means signatures cannot be checked, which the
        // app reports rather than silently accepting anything.
        buildConfigField(
            "String",
            "RUNTIME_SIGNING_KEYS",
            "\"${project.findProperty("droidrunner.runtimeSigningKeys") ?: ""}\"",
        )

        // Repo whose GitHub Releases host the runtime bundle (runtime-* tags).
        buildConfigField("String", "RUNTIME_REPO", "\"${project.findProperty("droidrunner.runtimeRepo") ?: ""}\"")

        // GPL "corresponding source" pointers for the proot binaries shipped in
        // the APK, read from the script that actually builds them so the About
        // screen can never drift from what was compiled.
        val prootScript = rootProject.file("runtime/build-proot.sh").readText()
        fun pinnedValue(name: String): String {
            // Matches e.g. PROOT_COMMIT="${PROOT_COMMIT:-<sha>}"
            val pattern = Regex(name + "=\"\\$\\{" + name + ":-([^}]*)\\}\"")
            return pattern.find(prootScript)?.groupValues?.get(1).orEmpty()
        }
        buildConfigField("String", "PROOT_COMMIT", "\"${pinnedValue("PROOT_COMMIT")}\"")
        buildConfigField("String", "TALLOC_VERSION", "\"${pinnedValue("TALLOC_VERSION")}\"")

        ndk.abiFilters += "arm64-v8a"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        create("release") {
            // CI provides a real keystore via env; local builds fall back to
            // the debug key below so the APK stays installable.
            val keystore = System.getenv("ANDROID_KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures.compose = true
    buildFeatures.buildConfig = true
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    // proot must exist as real files under nativeLibraryDir so the app can
    // exec them; run runtime/build-proot.sh once to populate jniLibs.
    packaging.jniLibs.useLegacyPackaging = true
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Single source of truth for the CLI: the copy the app installs into the
// guest is generated from runtime/droidrunner-device at build time.
val generatedAssets = layout.buildDirectory.dir("generated/deviceCliAsset")

val copyDeviceCli = tasks.register<Copy>("copyDeviceCli") {
    from(rootProject.file("runtime/droidrunner-device"))
    into(generatedAssets)
}

// AGP 9 refuses a Provider here — it cannot tell generated from static
// sources through one — so the path is resolved eagerly. It is a build
// directory, so the value does not depend on anything configured later.
android.sourceSets.getByName("main").assets.srcDir(generatedAssets.get().asFile)

tasks.named("preBuild") { dependsOn(copyDeviceCli) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Runs caller-supplied models; the NNAPI delegate is how a job reaches an
    // accelerator the device actually exposes.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in unit tests; use the real implementation.
    testImplementation("org.json:json:20260814")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// A published release must never be debug-signed: Android refuses to upgrade
// across a signature change, and reinstalling this app loses the runner
// registration and the stored GitHub credentials. This fires only when a
// release APK is actually assembled, so debug builds can still carry a tag.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(hasReleaseKey || releaseTag == null) {
            "Refusing to package $releaseTag with the debug key: configure " +
                "ANDROID_KEYSTORE_FILE (CI: the ANDROID_KEYSTORE_BASE64 secret). " +
                "Publishing a debug-signed release would strand every installed device."
        }
    }
}
