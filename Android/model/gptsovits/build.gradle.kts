plugins {
    id("com.android.library")
}

android {
    namespace = "dev.renakoni.voicebox.model.gptsovits"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    defaultConfig {
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_shared"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    implementation(project(":core"))
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    val runtime = providers.gradleProperty("voiceboxOrtAar").orNull
    if (runtime == null) {
        implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    } else {
        compileOnly(files(rootProject.file(runtime)))
        testImplementation(files(rootProject.file(runtime)))
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
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
tasks.named("preBuild").configure { dependsOn(syncLanguageAssets) }

androidComponents.onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/languageAssets").get().asFile.path)
}
