plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.slidemill.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slidemill.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // A fixed key checked into the repo, so every build (local or CI) can update the installed app.
    // It's a sideloaded personal app; for a store release, use a private key instead.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../slidemill-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
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
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/sharedAssets"))
}

// The TikTok poster is shared with the browser extension: one source of truth.
val copySharedPoster by tasks.registering(Copy::class) {
    from(rootProject.file("../extension/tiktok-inject.js"))
    into(layout.buildDirectory.dir("generated/sharedAssets"))
}
tasks.named("preBuild") { dependsOn(copySharedPoster) }
