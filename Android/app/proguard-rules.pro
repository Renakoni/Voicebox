# The custom ORT AAR has no consumer rules. Its JNI accesses Java classes and
# fields by name; NativeDecoderLoop also reads OrtSession.nativeHandle.
-keep class ai.onnxruntime.** { *; }

# Called by name from native_decoder_loop.cpp, including lambda implementations.
-keep interface dev.renakoni.voicebox.model.gptsovits.NativeDecoderLoop$DecoderProgressCallback { *; }
-keepclassmembers class * implements dev.renakoni.voicebox.model.gptsovits.NativeDecoderLoop$DecoderProgressCallback {
    public void onProgress(int, int);
}

# Preserve JNI entry points for the decoder, resampler, OpenJTalk and SILK.
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
