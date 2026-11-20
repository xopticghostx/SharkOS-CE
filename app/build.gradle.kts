import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Private keys live in local.properties (not shared). Missing key = the map falls back to OpenFreeMap.
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.isFile) {
    val input = FileInputStream(localPropsFile)
    try { localProps.load(input) } finally { input.close() }
}
val stadiaKey: String = localProps.getProperty("stadia.apiKey", "")

// Release signing: keystore.properties (git-ignored, next to local.properties) holds storeFile, storePassword,
// keyAlias and keyPassword. Never commit it or the .jks file. Updates only install over an existing copy when the
// new APK is signed with the same key, so keep the keystore backed up.
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKey = keystorePropsFile.isFile
if (hasReleaseKey) {
    val input = FileInputStream(keystorePropsFile)
    try { keystoreProps.load(input) } finally { input.close() }
}

android {
    namespace = "com.sharkhome.launcher"
    compileSdk = 35

    // The native build folder stays off Google Drive: Drive's file times make ninja loop ("still dirty after 100 tries").
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1"; buildStagingDirectory = File(System.getProperty("java.io.tmpdir"), "sharkos-ce-cxx") } }
    packaging { jniLibs { useLegacyPackaging = true } }
    defaultConfig {
        applicationId = "com.sharkhome.launcher"
        minSdk = 29
        targetSdk = 35
        versionCode = 96
        versionName = "0.6.776"
        // The Shark 6 head unit is arm64; shipping only that keeps MapLibre's native library from adding ~30 MB.
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "STADIA_KEY", "\"$stadiaKey\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No keystore.properties: fall back to the debug key so the build still works, but do not publish this APK.
                logger.warn("WARNING: keystore.properties not found, so the release build is signed with the DEBUG key. Do not publish it.")
                signingConfig = signingConfigs.getByName("debug")
            }
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
        // dadb's dependencies each ship the same licence/notice files.
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // ADB client: talks to the head unit's own adb (127.0.0.1:5555) for installing APKs and granting permissions.
    implementation("dev.mobile:dadb:1.2.9")
    // Vector maps for the dashboard card (OpenFreeMap styles: no account or key needed).
    implementation("org.maplibre.gl:android-sdk:11.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Bundle the wake helper (package tunein.player) inside the app, so SharkOS-CE can install it itself over the head
// unit's adb with no download and no tap. Each build carries a helper built and signed with the same key.
listOf("debug", "release").forEach { variant ->
    val cap = variant.replaceFirstChar { it.uppercase() }
    val outDir = layout.buildDirectory.dir("generated/wakehelper/$variant")
    val bundle = tasks.register<Copy>("bundleWakeHelper$cap") {
        dependsOn(":wakehelper:assemble$cap")
        from(project(":wakehelper").layout.buildDirectory.dir("outputs/apk/$variant")) {
            include("*.apk")
            rename { "wakehelper.apk" }
        }
        into(outDir)
    }
    android.sourceSets.getByName(variant).assets.srcDir(outDir)
    // Everything that reads this variant's assets (the asset merge and the lint model/report tasks) must run after the
    // copy, or a clean release build fails Gradle's implicit-dependency check.
    tasks.matching { t ->
        t.name == "merge${cap}Assets" || (t.name.contains("Lint", ignoreCase = true) && t.name.contains(cap))
    }.configureEach { dependsOn(bundle) }
}
