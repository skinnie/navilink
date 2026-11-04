import java.util.Properties

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // The protocol and file code lives in "jvmShared" so it can be unit-tested on the desktop JVM.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withAndroidTarget()
                withJvm()
            }
        }
    }
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.documentfile:documentfile:1.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
        val jvmSharedMain by getting {
            dependencies { implementation(project(":osmimg")) }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        val desktopTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
    }
}

android {
    namespace = "app.navilink"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.navilink"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
    }
    // One fixed key for every build, so a new APK installs over the old one.
    // Locally: nl.keystore / nl.keystore.password in local.properties. CI: NL_KEYSTORE / NL_KEYSTORE_PASSWORD.
    // Without either, Android's own debug key is used.
    val local = Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) } }
    val keyFile = (System.getenv("NL_KEYSTORE") ?: local.getProperty("nl.keystore"))?.let { file(it) }?.takeIf { it.isFile }
    val keyPassword = System.getenv("NL_KEYSTORE_PASSWORD") ?: local.getProperty("nl.keystore.password")
    if (keyFile != null && keyPassword != null) {
        val navilink = signingConfigs.create("navilink") {
            storeFile = keyFile
            storePassword = keyPassword
            keyAlias = "navilink"
            this.keyPassword = keyPassword
        }
        buildTypes.getByName("debug").signingConfig = navilink
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Desktop window: building and installing Garmin maps (needs a PC: mkgmap wants desktop Java and ~2 GB).
compose.desktop {
    application {
        mainClass = "app.navilink.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
            )
            packageName = "NaviLink"
            packageVersion = "1.0.0" // installers need MAJOR > 0
            modules("java.xml")
        }
    }
}

tasks.withType<Test>().configureEach {
    listOf("nl.gpi", "nl.shots").forEach { k -> project.findProperty(k)?.let { systemProperty(k, it) } }
}
