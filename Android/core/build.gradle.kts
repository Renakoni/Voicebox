plugins {
    id("com.android.library")
}

android {
    namespace = "dev.renakoni.voicebox.core"
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
    }
    buildTypes {
        debug { ndk.abiFilters += providers.gradleProperty("voiceboxAbi").getOrElse("arm64-v8a") }
        release { ndk.abiFilters += "arm64-v8a" }
    }
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 30
        buildConfigField("String", "VERSION_NAME", "\"${providers.gradleProperty("voiceboxVersionName").get()}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":bridge-api"))
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("androidx.annotation:annotation:1.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
