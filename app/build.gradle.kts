plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.devenus.droidrunner"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.devenus.droidrunner"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Public identifiers of the DroidRunner GitHub App used for device-flow login.
        // Self-builders register their own app and set these in gradle.properties.
        buildConfigField("String", "GITHUB_APP_CLIENT_ID", "\"${project.findProperty("droidrunner.githubAppClientId") ?: ""}\"")
        buildConfigField("String", "GITHUB_APP_SLUG", "\"${project.findProperty("droidrunner.githubAppSlug") ?: ""}\"")
        // Repo whose GitHub Releases host the runtime bundle (runtime-* tags).
        buildConfigField("String", "RUNTIME_REPO", "\"${project.findProperty("droidrunner.runtimeRepo") ?: ""}\"")
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
            signingConfig = if (System.getenv("ANDROID_KEYSTORE_FILE") != null) {
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
    kotlinOptions.jvmTarget = "17"
    buildFeatures.compose = true
    buildFeatures.buildConfig = true
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    // proot must exist as real files under nativeLibraryDir so the app can
    // exec them; run runtime/build-proot.sh once to populate jniLibs.
    packaging.jniLibs.useLegacyPackaging = true
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
