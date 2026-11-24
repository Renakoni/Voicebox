import com.android.build.api.artifact.SingleArtifact
import org.gradle.process.ExecOperations
import javax.inject.Inject

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.library")
}

android {
    namespace = "dev.renakoni.voicebox.model.gptsovits"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    defaultConfig {
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_shared"
    }
    buildTypes {
        debug { ndk.abiFilters += providers.gradleProperty("voiceboxAbi").getOrElse("arm64-v8a") }
        release { ndk.abiFilters += "arm64-v8a" }
    }
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core"))
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    val runtime = providers.gradleProperty("voiceboxOrtAar").orNull
    if (runtime == null) {
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    } else {
        compileOnly(files(rootProject.file(runtime)))
    }
}

val packageOrt by configurations.creating { isTransitive = false }
val packageZip by configurations.creating { isTransitive = false }
dependencies {
    val custom = providers.gradleProperty("voiceboxOrtAar").orNull
    add(packageOrt.name, if (custom == null) "com.microsoft.onnxruntime:onnxruntime-android:1.27.0@aar" else files(rootProject.file(custom)))
    add(packageZip.name, "net.lingala.zip4j:zip4j:2.11.5")
}

abstract class BundleGptBridge @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile abstract val component: RegularFileProperty
    @get:InputFiles abstract val ort: ConfigurableFileCollection
    @get:InputFiles abstract val zip: ConfigurableFileCollection
    @get:InputFile abstract val packer: RegularFileProperty
    @get:InputFile abstract val platform: RegularFileProperty
    @get:InputFile abstract val audit: RegularFileProperty
    @get:Input abstract val buildTools: Property<String>
    @get:Input abstract val abi: Property<String>
    @get:Input abstract val commit: Property<String>
    @get:Input abstract val releaseUrl: Property<String>
    @get:Internal abstract val sdk: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun bundle() {
        exec.exec {
            commandLine("python", packer.get().asFile, "--aar", component.get().asFile, "--runtime", ort.singleFile,
                "--zip4j", zip.singleFile, "--sdk", sdk.get().asFile, "--build-tools", buildTools.get(), "--output", output.get().asFile,
                "--platform", platform.get().asFile, "--audit", audit.get().asFile, "--abi", abi.get(), "--commit", commit.get(),
                "--release-url", releaseUrl.get())
        }
    }
}

androidComponents.onVariants { variant ->
    tasks.register<BundleGptBridge>("bundle${variant.name.replaceFirstChar(Char::uppercaseChar)}Bridge") {
        component.set(variant.artifacts.get(SingleArtifact.AAR))
        ort.from(packageOrt)
        zip.from(packageZip)
        packer.set(layout.projectDirectory.file("build_package.py"))
        audit.set(layout.projectDirectory.file("bridge-assets.json"))
        platform.set(androidComponents.sdkComponents.bootClasspath.map { it.first() })
        sdk.set(androidComponents.sdkComponents.sdkDirectory)
        buildTools.set(android.buildToolsVersion)
        abi.set(if (variant.name == "debug") providers.gradleProperty("voiceboxAbi").getOrElse("arm64-v8a") else "arm64-v8a")
        commit.set(providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() })
        releaseUrl.set("https://github.com/Renakoni/Voicebox/releases/download/gptsovits-runtime-v4")
        output.set(layout.buildDirectory.dir("outputs/bridge/${variant.name}"))
    }
}

// Common inference models are shipped as one resource package, not copied
// into every character model package. The source path is configurable so
// generated model binaries do not need to live in the Git worktree.
val runtimeAssetSource = providers.gradleProperty("voiceboxRuntimeAssets")
    .orElse(providers.environmentVariable("VOICEBOX_RUNTIME_ASSETS"))
    .orElse(providers.provider {
        throw GradleException("Set -PvoiceboxRuntimeAssets=<GenieData> or VOICEBOX_RUNTIME_ASSETS to package runtime assets.")
    })
val pythonExecutable = providers.gradleProperty("voiceboxPython")
    .orElse(providers.environmentVariable("VOICEBOX_PYTHON"))
    .orElse("python")
val runtimeAssetOutput = layout.buildDirectory.dir("generated/runtimeAssets")
val runtimeAssetManifestOutput = layout.buildDirectory.file(
    "generated/runtimeAssetManifest/chinese-hubert-base_weights_fp16_manifest.json",
)

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
    val generator = layout.projectDirectory.file("tools/generate_runtime_asset_manifest.py").asFile
    inputs.file(generator)
    inputs.file(File(sourceRoot, "chinese-hubert-base/chinese-hubert-base.onnx"))
    inputs.file(File(sourceRoot, "chinese-hubert-base/chinese-hubert-base_weights_fp16.bin"))
    outputs.file(runtimeAssetManifestOutput)
    workingDir(rootProject.projectDir.parentFile)
    commandLine(
        pythonExecutable.get(),
        generator.absolutePath,
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
                "Set -PvoiceboxRuntimeAssets=<GenieData> or VOICEBOX_RUNTIME_ASSETS."
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
    val languages = rootProject.file("../../assets/language-frontends")
    doFirst { check(File(languages, "english/encoder.onnx").isFile && File(languages, "japanese/sys.dic").isFile) {
        "Prepare language resources before building the model resource archive."
    } }
    from(languages) { into("RuntimeAssets/language_frontends") }
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
