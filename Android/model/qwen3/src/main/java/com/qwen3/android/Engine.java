package com.qwen3.android;

/** JNI names and callback signatures exported by the qwen3-android runtime. */
public final class Engine {
    private Engine() {}

    public interface Sink {
        boolean receive(float[] pcm);
        boolean isCancelled();
    }

    public static native long open(byte[] talker, byte[] codec, byte[] device, byte[] codecDevice);
    public static native void close(long handle);
    public static native boolean synthesizeConfigured(long handle, byte[] text, byte[] language, byte[] reference,
            byte[] transcript, byte[] speaker, boolean doSample, float temperature, int topK, float topP,
            float repetitionPenalty, boolean subtalkerDoSample, float subtalkerTemperature, int subtalkerTopK,
            float subtalkerTopP, int maxNewTokens, long seed, Sink sink);
}
