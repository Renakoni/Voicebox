plugins {
    id("com.android.library")
}

android {
    namespace = "dev.renakoni.voicebox.bridge"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
