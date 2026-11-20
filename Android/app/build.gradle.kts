import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

// Release builds are always signed locally. The keystore and password file
// stay ignored; a missing pair must fail the release task instead of emitting
// an APK that cannot be installed over the previous release.
val releaseKeystoreFile = rootProject.file("signing/voicebox-release.jks")
val releasePasswordFile = rootProject.file("signing/voicebox-release.password")
val releaseSigningMaterialAvailable = releaseKeystoreFile.isFile && releasePasswordFile.isFile
val releaseSigningPassword = if (releasePasswordFile.isFile) {
    releasePasswordFile.readText(Charsets.UTF_8).trim()
} else {
    ""
}
abstract class VerifyReleaseSigningMaterialTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val keystoreFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val passwordFile: RegularFileProperty

    @TaskAction
    fun verify() {
        check(keystoreFile.get().asFile.isFile && passwordFile.get().asFile.isFile) {
            "Missing Android release signing material under ${keystoreFile.get().asFile.parentFile.absolutePath}."
        }
        check(passwordFile.get().asFile.readText(Charsets.UTF_8).trim().isNotEmpty()) {
            "Android release signing password file is empty."
        }
    }
}
val verifyReleaseSigningMaterial = tasks.register<VerifyReleaseSigningMaterialTask>(
    "verifyReleaseSigningMaterial",
) {
    keystoreFile.set(releaseKeystoreFile)
    passwordFile.set(releasePasswordFile)
}

android {
    namespace = "dev.renakoni.voicebox"
    ndkVersion = "28.2.13676358"
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets").get().asFile)
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.renakoni.voicebox"
        minSdk = 30
        targetSdk = 36
        versionCode = providers.gradleProperty("voiceboxVersionCode").get().toInt()
        versionName = providers.gradleProperty("voiceboxVersionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningMaterialAvailable) {
                storeFile = releaseKeystoreFile
                storePassword = releaseSigningPassword
                keyAlias = "voicebox_release"
                keyPassword = releaseSigningPassword
            }
        }
    }

    buildTypes {
        debug {
            // MuMu functional checks use x86_64; the release ABI is fixed below.
            ndk { abiFilters += providers.gradleProperty("voiceboxAbi").getOrElse("arm64-v8a") }
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents.onVariants { variant ->
    val name = variant.name.replaceFirstChar(Char::uppercaseChar)
    android.sourceSets.getByName(variant.name).assets.srcDir(rootProject.file("model/gptsovits/build/outputs/bridge/${variant.name}"))
    tasks.matching { it.name == "pre${name}Build" || it.name == "merge${name}Assets" }.configureEach { dependsOn(":model:gptsovits:bundle${name}Bridge") }
}

val syncLicenseAssets = tasks.register<Sync>("syncLicenseAssets") {
    from(rootProject.file("../LICENSE"), rootProject.file("../NOTICE"))
    from(rootProject.file("../licenses")) { into("third-party") }
    into(layout.buildDirectory.dir("generated/licenseAssets/voicebox-licenses"))
}
tasks.named("preBuild").configure { dependsOn(syncLicenseAssets) }

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    jvmArgs("-Xmx96m")
}

afterEvaluate {
    tasks.configureEach {
        if (name.startsWith("assemble") && name.endsWith("Release")) {
            dependsOn(verifyReleaseSigningMaterial)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.core:core-ktx:1.19.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Align Compose's transitive Espresso version with AndroidX Test 1.3.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
