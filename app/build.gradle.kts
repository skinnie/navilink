import java.util.Properties

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion = providers.gradleProperty("navilink.version").get()
val appVersionCode = providers.gradleProperty("navilink.versionCode").get().toInt()
// Windows and macOS installers refuse a major version of 0, so 0.5.0 is 1.5.0 there (Linux and Android keep 0.5.0).
val installerVersion = if (appVersion.startsWith("0.")) "1." + appVersion.removePrefix("0.") else appVersion

// app.navilink.core.APP_VERSION, so the app can say which version it is (User-Agent, About).
val generateVersion = tasks.register("generateVersion") {
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", appVersion)
    outputs.dir(out)
    doLast {
        val f = out.get().file("app/navilink/core/Version.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package app.navilink.core\n\n/** From gradle.properties (navilink.version). */\nconst val APP_VERSION = \"$appVersion\"\n")
    }
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
            kotlin.srcDir(generateVersion.map { it.outputs.files.singleFile })
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
        versionCode = appVersionCode
        versionName = appVersion
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
    // The shared code's data files (Foursquare index) go into the APK too.
    sourceSets["main"].resources.srcDir("src/jvmSharedMain/resources")
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
            packageVersion = appVersion
            description = "Garmin maps, POIs and device manager"
            vendor = "NaviLink"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.xml", "java.instrument", "jdk.unsupported", "jdk.crypto.ec")
            linux {
                packageName = "navilink"
                iconFile.set(project.file("icons/navilink.png"))
                menuGroup = "Utility"
                appCategory = "utils"
                shortcut = true
                debMaintainer = "5618623+skinnie@users.noreply.github.com"
                debPackageVersion = appVersion
            }
            windows {
                iconFile.set(project.file("icons/navilink.ico"))
                menuGroup = "NaviLink"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                // Fixed for good: Windows uses it to replace the installed version instead of adding a second one.
                upgradeUuid = "298a7d54-a27c-413d-87e1-2e476bb2658d"
                msiPackageVersion = installerVersion
            }
            macOS {
                iconFile.set(project.file("icons/navilink.icns"))
                bundleID = "app.navilink"
                dmgPackageVersion = installerVersion
                packageVersion = installerVersion
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // Test files go on the project's drive, not the system disk's /tmp.
    layout.buildDirectory.dir("test-tmp").get().asFile.let { it.mkdirs(); systemProperty("java.io.tmpdir", it.path) }
    listOf("nl.gpi", "nl.shots", "nl.poisets", "nl.poilive", "nl.devdir", "nl.tiles").forEach { k -> project.findProperty(k)?.let { systemProperty(k, it) } }
}
