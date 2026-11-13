import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    // Kotlin comes from AGP 9 built-in Kotlin; the serialization runtime arrives through :core.
}

// --- Key injection -----------------------------------------------------------------------------
// OUTBOUND_KEY_FILE must point to a file of exactly 32 raw bytes. The bytes become a BuildConfig
// byte[] field. Nothing about the key is ever logged; error messages name only the variable.
val keyBytes: ByteArray = run {
    val path = System.getenv("OUTBOUND_KEY_FILE")
    if (path.isNullOrBlank()) {
        throw GradleException("OUTBOUND_KEY_FILE is not set (expected path to a 32-byte key file)")
    }
    val f = File(path)
    if (!f.isFile) throw GradleException("OUTBOUND_KEY_FILE does not point to a readable file")
    val b = f.readBytes()
    if (b.size != 32) throw GradleException("OUTBOUND_KEY_FILE must contain exactly 32 bytes")
    b
}
val keyLiteral = "new byte[]{" + keyBytes.joinToString(",") { it.toInt().toString() } + "}"

android {
    namespace = "dev.local.outboundcompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.local.outboundcompanion"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("byte[]", "OUTBOUND_KEY", keyLiteral)
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
}

// --- POI + map feature + guide tables: one source of truth (core/src/main/resources/pois.json, features.json and guides.json), copied into a
// generated assets dir under build/ before each build (so `clean` removes it). The app reads them from assets; core tests
// read the classpath resources.
val poiAssetsDir = layout.buildDirectory.dir("generated/poiAssets")
val copyPois = tasks.register<Copy>("copyPois") {
    from(rootProject.file("core/src/main/resources/pois.json"), rootProject.file("core/src/main/resources/features.json"), rootProject.file("core/src/main/resources/guides.json"))
    into(poiAssetsDir)
}
android.sourceSets.getByName("main").assets.directories.add(poiAssetsDir.get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(copyPois) }
