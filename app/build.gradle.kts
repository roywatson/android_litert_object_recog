import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Opt-in local vendor SDK. Portable builds do not need the NDK or QAIRT.
val localBuildFile = rootProject.file(".local/build.properties")
val localBuild = Properties().apply {
    if (localBuildFile.isFile) localBuildFile.inputStream().use { load(it) }
}
val qualcommNpuSetting = providers.gradleProperty("qualcommNpuDir").orNull
    ?: localBuild.getProperty("qualcommNpuDir")
val qualcommNpuDir = qualcommNpuSetting?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
val qairtVersion = "2.50.0.260828"
val releaseSigningFile = rootProject.file(".local/release-signing.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
val hasReleaseSigning = !releaseSigning.getProperty("storePassword").isNullOrBlank()

android {
    namespace = "com.roywatson.android_litert_object_recog"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.roywatson.android_litert_object_recog"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("boolean", "QUALCOMM_NPU_ENABLED", (qualcommNpuDir != null).toString())
        buildConfigField("String", "LITERT_VERSION", "\"${libs.versions.litert.get()}\"")
        buildConfigField("String", "QAIRT_VERSION", "\"${if (qualcommNpuDir != null) qairtVersion else "not-bundled"}\"")
        if (qualcommNpuDir != null) {
            minSdk = 31
            ndk { abiFilters += "arm64-v8a" }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testBuildType = providers.gradleProperty("validationBuildType").getOrElse("debug")

    signingConfigs {
        if (hasReleaseSigning) {
            create("localRelease") {
                storeFile = rootProject.file(requireNotNull(releaseSigning.getProperty("storeFile")))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
                keyPassword = releaseSigning.getProperty("keyPassword")?.takeIf { it.isNotBlank() }
                    ?: releaseSigning.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            // Coexist with the existing debug-signed app; no uninstall or data loss.
            applicationIdSuffix = ".release"
            manifestPlaceholders["appLabel"] = "Vision Lab Release"
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("localRelease")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    androidResources { noCompress += "tflite" }

    if (qualcommNpuDir != null) {
        ndkVersion = "28.2.13676358"
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
        }
        sourceSets.getByName("main") {
            jniLibs.srcDir(qualcommNpuDir)
            assets.srcDir(qualcommNpuDir.resolve("notices"))
            assets.srcDir(qualcommNpuDir.resolve("provenance"))
        }
        // QAIRT must be addressable as files by the compiler and FastRPC loader.
        packaging.jniLibs.useLegacyPackaging = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    if (qualcommNpuDir != null) {
        implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:${libs.versions.litert.get()}")
    }
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

if (qualcommNpuDir != null) {
    val verifyQualcommRuntime by tasks.registering {
        val vendorDir = qualcommNpuDir
        val expectedVersion = "2.50.0.260828"
        val pinnedChecksums = rootProject.file("scripts/qairt-2.50.0.260828-checksums.json")
        inputs.dir(vendorDir)
        inputs.file(pinnedChecksums)
        doLast {
            val manifestFile = vendorDir.resolve("manifest.json")
            check(manifestFile.isFile) { "Run scripts/prepare_qairt.py first; missing $manifestFile" }
            val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
            check(manifest["qairt_version"] == expectedVersion) { "QAIRT version mismatch" }
            val files = manifest["files"] as Map<*, *>
            val pinned = (JsonSlurper().parse(pinnedChecksums) as Map<*, *>)["files"] as Map<*, *>
            val required = listOf("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so",
                "libQnnHtpV69Stub.so", "libQnnHtpV69Skel.so", "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so")
            for (name in required) {
                val library = vendorDir.resolve("arm64-v8a/$name")
                check(library.isFile) { "Missing QAIRT library: $library" }
                val expected = files[name] as? Map<*, *> ?: error("Missing vendor hash: $name")
                val actual = MessageDigest.getInstance("SHA-256").digest(library.readBytes())
                    .joinToString("") { "%02x".format(it) }
                val trusted = pinned[name] as Map<*, *>
                check(actual == expected["sha256"] && actual == trusted["sha256"]) { "Vendor checksum mismatch: $name" }
            }
            check(vendorDir.resolve("notices").listFiles()?.isNotEmpty() == true) { "QAIRT notices missing" }
        }
    }
    tasks.named("preBuild").configure { dependsOn(verifyQualcommRuntime) }
}
