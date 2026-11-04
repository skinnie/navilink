import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// OSM -> Garmin .img: regions, download, splitter + mkgmap, the clean style, device rules.
// Plain JVM (no Android or NaviLink code) so any project can use it, as a library or as
// `java -jar osmimg.jar` (built by the cliJar task into osmimg/build/cli/osmimg.jar).
plugins {
    kotlin("jvm")
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    // Real builds are off by default: -Pnl.maps=<region> [-Pnl.maps.max=<bytes>] [-Pnl.maps.bike=true]
    listOf("nl.maps", "nl.maps.max", "nl.maps.bike", "nl.imgs").forEach { k -> project.findProperty(k)?.let { systemProperty(k, it) } }
    if (project.hasProperty("nl.maps")) { outputs.upToDateWhen { false }; testLogging.showStandardStreams = true }
}

// One runnable jar with the Kotlin runtime inside.
tasks.register<Jar>("cliJar") {
    archiveFileName.set("osmimg.jar")
    destinationDirectory.set(layout.buildDirectory.dir("cli"))
    manifest { attributes("Main-Class" to "osmimg.CliKt") }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
