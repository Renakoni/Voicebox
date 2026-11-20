# Preserve host JNI entry points for shared audio and SILK.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Conscrypt includes adapters for Android 4.x hidden APIs. This app requires
# Android 11+, so those platform classes are absent and the adapters are unused.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# The separately compiled packages resolve their API and Kotlin runtime through the parent loader.
-keep class dev.renakoni.voicebox.bridge.** { *; }
-keep class dev.renakoni.voicebox.core.** { *; }
-keep interface dev.renakoni.voicebox.bridge.** { *; }
-keep class kotlin.** { *; }
