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
        versionCode = 15
        versionName = "15.0.0"
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
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/sharedAssets"))
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// One source of truth, shared with the server and the browser extension: the slide fonts (fonts/)
// and the TikTok page script (extension/tiktok-inject.js).
val copySharedAssets by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/sharedAssets"))
    from(rootProject.file("../extension/tiktok-inject.js"))
    from(rootProject.file("../fonts")) { into("fonts") }
}
tasks.named("preBuild") { dependsOn(copySharedAssets) }
