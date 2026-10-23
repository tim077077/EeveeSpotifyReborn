import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Slidemill engine as plain Kotlin/JVM: no Android imports, so it runs (and is tested) on any JVM.
// The Android app supplies the platform pieces (rendering, video, key storage, notifications).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Phone engine on the desktop with a fake AI, for browser UI tests.
tasks.register<JavaExec>("harness") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.slidemill.core.HarnessKt")
    workingDir = rootProject.projectDir
}
