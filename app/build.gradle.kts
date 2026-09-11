import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Release signing is configured from, in order of preference:
 *  1. `keystore.properties` in the repo root (git-ignored) with keys
 *     `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; or
 *  2. the environment variables `GPSTRACK_STORE_FILE`, `GPSTRACK_STORE_PASSWORD`,
 *     `GPSTRACK_KEY_ALIAS`, `GPSTRACK_KEY_PASSWORD`.
 * When neither is present, `assembleRelease` still runs and produces an unsigned APK.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.sennheiser1986.gpstrack"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sennheiser1986.gpstrack"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default sharing server baked into the build. It is a placeholder: point the app at
        // your own relay either here at build time
        //   ./gradlew assembleRelease -PshareServerUrl=https://gpstrack.example.org
        // or at runtime from the Share tab. An empty value simply leaves sharing unconfigured.
        val shareServerUrl = (project.findProperty("shareServerUrl") as String?)
            ?: "https://gpstrack.example.org"
        buildConfigField("String", "DEFAULT_SHARE_SERVER_URL", "\"$shareServerUrl\"")

        // Offline vector maps (MapsForge). The app browses this server's directory tree and
        // downloads regions from it; override with -PofflineMapBaseUrl=... to use a mirror.
        val offlineMapBaseUrl = (project.findProperty("offlineMapBaseUrl") as String?)
            ?: "https://download.mapsforge.org/maps/v5"
        buildConfigField("String", "OFFLINE_MAP_BASE_URL", "\"${offlineMapBaseUrl.trimEnd('/')}\"")
    }

    // Two installable copies of the app, so a phone (or one emulator) can run two instances
    // that share location with each other. "primary" is the app you publish; "secondary"
    // installs alongside it under a ".b" application id with its own database and identity, and
    // is only a development convenience.
    flavorDimensions += "instance"
    productFlavors {
        create("primary") {
            dimension = "instance"
            isDefault = true
            resValue("string", "application_name", "GPS Track")
        }
        create("secondary") {
            dimension = "instance"
            applicationIdSuffix = ".b"
            versionNameSuffix = "-b"
            resValue("string", "application_name", "GPS Track B")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingValue("storeFile", "GPSTRACK_STORE_FILE")
                ?: "gpstrack-upload-key.jks"
            val resolvedStore = rootProject.file(storeFilePath)
            if (resolvedStore.exists()) {
                storeFile = resolvedStore
                storePassword = signingValue("storePassword", "GPSTRACK_STORE_PASSWORD") ?: ""
                keyAlias = signingValue("keyAlias", "GPSTRACK_KEY_ALIAS") ?: "gpstrack"
                keyPassword = signingValue("keyPassword", "GPSTRACK_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            // Sign only when a keystore is actually configured; otherwise assembleRelease still
            // produces an (unsigned) APK/AAB on a fresh checkout.
            val store = signingValue("storeFile", "GPSTRACK_STORE_FILE") ?: "gpstrack-upload-key.jks"
            signingConfig = signingConfigs.getByName("release")
                .takeIf { rootProject.file(store).exists() }
            // R8 is left off so a fresh checkout builds and runs without tuning keep rules for
            // osmdroid / mapsforge / ZXing / Room. Turn both on and extend proguard-rules.pro
            // when you are ready to shrink the release.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.osmdroid.android)
    implementation(libs.osmdroid.mapsforge)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    testImplementation("junit:junit:4.13.2")
    // The android.jar org.json stubs throw at test time; this is the real implementation for
    // the JVM test classpath (BackupCodec tests).
    testImplementation("org.json:json:20240303")
}
