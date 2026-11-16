plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * SharkOS-CE wake helper.
 *
 * BYD's power service (com.ts.appservice.power, CarPowerBinder.forceStopThirdApp) force-stops every
 * user-installed app each time the car goes to standby, except the packages in its FORCE_STOP_WHITELIST:
 * "tunein.player" and "com.byd.tunein". A force-stopped app gets no broadcasts at all until something
 * starts it again, so SharkOS-CE could never come back by itself after the car slept.
 *
 * This helper uses the whitelisted package name so it keeps running through standby.
 * It listens for BYD's display-on event and starts SharkOS-CE's StartupService,
 * which restores settings and brings SharkOS-CE to the front. It has no screen and no other job.
 *
 * Note: it can't be installed next to another app using the same whitelisted package name (e.g. TuneIn).
 */
android {
    namespace = "com.sharkhome.wakehelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "tunein.player"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Must be signed with the same key as SharkOS-CE: it uses SharkOS-CE's signature permission.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
