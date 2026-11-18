import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

// Common inference models are shipped as one resource package, not copied
// into every character model package. The source path is configurable so
// generated model binaries do not need to live in the Git worktree.
val runtimeAssetSource = providers.gradleProperty("genieRuntimeAssets")
    .orElse(providers.environmentVariable("GENIE_TTS_RUNTIME_ASSETS"))
    .orElse(providers.provider {
        throw GradleException("Set -PgenieRuntimeAssets=<GenieData> or GENIE_TTS_RUNTIME_ASSETS to package runtime assets.")
    })
val pythonExecutable = providers.gradleProperty("geniePython")
    .orElse(providers.environmentVariable("GENIE_TTS_PYTHON"))
    .orElse("python")
val runtimeAssetOutput = layout.buildDirectory.dir("generated/runtimeAssets")
val runtimeAssetManifestOutput = layout.buildDirectory.file(
    "generated/runtimeAssetManifest/chinese-hubert-base_weights_fp16_manifest.json",
)

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

abstract class HighCompressionRuntimeAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @TaskAction
    fun createArchive() {
        val sourceRoot = sourceDirectory.get().asFile
        val outputFile = archiveFile.get().asFile
        outputFile.parentFile.mkdirs()
        val temporaryFile = File(
            outputFile.parentFile,
            ".${outputFile.name}.${System.nanoTime()}.tmp",
        )
        try {
            temporaryFile.outputStream().buffered().use { output ->
                ZipOutputStream(output).use { archive ->
                    // Use the same high-compression policy as character packages.
                    archive.setLevel(Deflater.BEST_COMPRESSION)
                    sourceRoot.walkTopDown()
                        .filter(File::isFile)
                        .sortedBy { it.relativeTo(sourceRoot).invariantSeparatorsPath }
                        .forEach { file ->
                            val entryName = file.relativeTo(sourceRoot).invariantSeparatorsPath
                            archive.putNextEntry(ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(archive) }
                            archive.closeEntry()
                        }
                }
            }
            Files.move(
                temporaryFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporaryFile.delete()
        }
    }
}

val generateRuntimeAssetManifest = tasks.register<Exec>("generateRuntimeAssetManifest") {
    val sourceRoot = file(runtimeAssetSource.get())
    inputs.file(File(sourceRoot, "chinese-hubert-base/chinese-hubert-base.onnx"))
    inputs.file(File(sourceRoot, "chinese-hubert-base/chinese-hubert-base_weights_fp16.bin"))
    outputs.file(runtimeAssetManifestOutput)
    workingDir(rootProject.projectDir.parentFile)
    commandLine(
        pythonExecutable.get(),
        "tools/generate_runtime_asset_manifest.py",
        File(sourceRoot, "chinese-hubert-base/chinese-hubert-base.onnx").absolutePath,
        File(sourceRoot, "chinese-hubert-base/chinese-hubert-base_weights_fp16.bin").absolutePath,
        runtimeAssetManifestOutput.get().asFile.absolutePath,
    )
}
val syncRuntimeAssets = tasks.register<Sync>("syncRuntimeAssets") {
    dependsOn(generateRuntimeAssetManifest)
    val sourceRoot = file(runtimeAssetSource.get())
    val requiredSources = listOf(
        File(sourceRoot, "chinese-hubert-base/chinese-hubert-base.onnx"),
        File(sourceRoot, "chinese-hubert-base/chinese-hubert-base_weights_fp16.bin"),
        File(sourceRoot, "speaker_encoder.onnx"),
        File(sourceRoot, "RoBERTa/RoBERTa.onnx"),
        File(sourceRoot, "RoBERTa/roberta_tokenizer/tokenizer.json"),
    )
    doFirst {
        val missing = requiredSources.filterNot(File::isFile)
        check(missing.isEmpty()) {
            "Missing Android runtime asset sources: ${missing.joinToString(", ")}. " +
                "Set -PgenieRuntimeAssets=<GenieData> or GENIE_TTS_RUNTIME_ASSETS."
        }
    }

    from(File(sourceRoot, "chinese-hubert-base/chinese-hubert-base.onnx")) {
        into("RuntimeAssets/chinese-hubert-base")
    }
    from(File(sourceRoot, "chinese-hubert-base/chinese-hubert-base_weights_fp16.bin")) {
        into("RuntimeAssets/chinese-hubert-base")
    }
    from(runtimeAssetManifestOutput) {
        into("RuntimeAssets/chinese-hubert-base")
    }
    from(File(sourceRoot, "speaker_encoder.onnx")) {
        into("RuntimeAssets")
    }
    from(File(sourceRoot, "v5-vocoder")) {
        into("RuntimeAssets/v5-vocoder")
    }
    from(File(sourceRoot, "RoBERTa/RoBERTa.onnx")) {
        into("RuntimeAssets/roberta-wwm-ext-large-onnx")
        rename { "model.onnx" }
    }
    from(File(sourceRoot, "RoBERTa/roberta_tokenizer")) {
        into("RuntimeAssets/roberta-wwm-ext-large-onnx/roberta_tokenizer")
    }
    into(runtimeAssetOutput)
}

tasks.register<HighCompressionRuntimeAssetsTask>("bundleRuntimeAssets") {
    dependsOn(syncRuntimeAssets)
    sourceDirectory.set(runtimeAssetOutput)
    archiveFile.set(
        layout.buildDirectory.file("outputs/runtime-assets/genie-tts-runtime-assets.zip"),
    )
}

android {
    namespace = "dev.rinstel.genie_tts"
    ndkVersion = "28.2.13676358"
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/languageAssets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets").get().asFile)
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.rinstel.genie_tts"
        minSdk = 30
        targetSdk = 36
        versionCode = 9
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_shared", "-DGENIE_ENABLE_NATIVE_DECODER=ON")
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
            ndk { abiFilters += providers.gradleProperty("genieAbi").getOrElse("arm64-v8a") }
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

val syncLanguageAssets = tasks.register<Sync>("syncLanguageAssets") {
    val source = rootProject.file("../../assets/language-frontends")
    from(source) { into("language_frontends") }
    into(layout.buildDirectory.dir("generated/languageAssets"))
    doFirst {
        check(File(source, "japanese/manifest.json").isFile && File(source, "english/manifest.json").isFile) {
            "Run tools/prepare_language_assets.py to prepare the offline language frontends."
        }
    }
}
val syncLicenseAssets = tasks.register<Sync>("syncLicenseAssets") {
    from(rootProject.file("../LICENSE"), rootProject.file("../NOTICE"))
    from(rootProject.file("../licenses")) { into("third-party") }
    into(layout.buildDirectory.dir("generated/licenseAssets/voicebox-licenses"))
}
tasks.named("preBuild").configure { dependsOn(syncLanguageAssets, syncLicenseAssets) }

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
    implementation("net.lingala.zip4j:zip4j:2.11.5")
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
    // Use the optimized CPU + WebGPU AAR for delivery; stock ORT supports local development.
    val customOrtAar = providers.gradleProperty("genieOrtAar").orNull
    if (customOrtAar == null) {
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    } else {
        val runtimeAar = rootProject.file(customOrtAar)
        check(runtimeAar.isFile) { "Missing ORT AAR: $runtimeAar" }
        implementation(files(runtimeAar))
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Align Compose's transitive Espresso version with AndroidX Test 1.3.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
