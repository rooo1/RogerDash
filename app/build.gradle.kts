import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key, kept outside the repo. keystore.properties (git-ignored) holds
// storeFile, storePassword, keyAlias and keyPassword. Without it, release builds are unsigned.
val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// The developer's own Google Maps key for the map view, from local.properties (MAPS_API_KEY=…), never the repo.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// -Ppersonal: a release build for YOUR OWN phone with your Maps key inside (Google's map, Maps SDK).
// Named "-personal"; never publish it. Without the flag, release builds carry no key (OpenStreetMap map).
val personal = project.hasProperty("personal")

android {
    namespace = "app.roger.dash"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.roger.dash"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Debug builds on your own phone may use your Maps key (empty = the free OpenStreetMap map).
            manifestPlaceholders["MAPS_API_KEY"] = local.getProperty("MAPS_API_KEY", "")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            // Never a personal key in an APK others download: anyone could pull it out. Public release = OpenStreetMap
            // map; -Ppersonal = your own phone, with your key.
            manifestPlaceholders["MAPS_API_KEY"] = if (personal) local.getProperty("MAPS_API_KEY", "") else ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true // SystemClock etc. return 0 in JVM tests
    }
}

// Output file name: RogerDash-<version>-debug.apk / RogerDash-<version>-release.apk
base {
    archivesName.set("RogerDash-${android.defaultConfig.versionName}" + if (personal) "-personal" else "")
}

dependencies {
    // Maps: open-source engine; tiles from OpenFreeMap (OpenStreetMap data, free, no key).
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // MapLibre's own HTTP client (already inside it): set up for OpenFreeMap's offline-download rules (OfflineMaps.kt).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Google's map, used instead when a debug build has a Google Maps key (MAPS_API_KEY in local.properties).
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    // One-tap "Turn on location?" dialog when Location is off.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real JSON parsing in JVM tests
}
