package dev.ming.local;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Maps Voicebox execution modes to the ming-android runner. */
final class Native {
    private static native void select(String runtime);
    private static native int synthesize(String[] arguments, String[] environment, String log);
    private static native int reference(String[] arguments, String[] environment, String log);

    static void configure(Path bundle, String mode, Map<String, String> env) throws Exception {
        if (mode.equals("webgpu")) {
            env.put("MING_CPU_SPIN", "1");
            env.put("MING_ARENA", "1");
            env.put("MING_REFERENCE_FLOW_BATCH", "8");
        }
        if (mode.equals("htp")) {
            Path qnn = bundle.resolve("qnn").toRealPath();
            env.remove("MING_PORTABLE");
            env.put("ADSP_LIBRARY_PATH", qnn + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp");
            env.put("MING_QNN_BACKEND", qnn.resolve("libQnnHtp.so").toString());
            env.put("MING_FLOW_QNN", "1");
            env.put("MING_FLOW_STATIC", bundle.resolve("htp").toString());
            env.put("MING_AGG_MODEL", bundle.resolve("models/aggregator-htp.onnx").toString());
            env.put("MING_FLOW_CONTEXT", bundle.resolve("flow-context.onnx").toString());
            env.put("MING_HTP_MODE", "burst");
            env.put("MING_PARK_HTP", "1");
            env.put("MING_BATCH_PARK_BETWEEN_FLOW", "1");
            env.put("MING_AUDIO_THREADS", "6");
            env.put("MING_OPS_LIBRARY", bundle.resolve("libming-mmla-bf16.so").toString());
            env.put("MING_BF16_RESIDUAL", "1");
            env.put("MING_MMLA_BATCHES", "24");
            env.put("MING_PREPACK", "1");
            env.put("MING_ARENA", "1");
        }
    }

    static void process(List<String> command, Map<String, String> environment, Path log) throws Exception {
        File root = new File(command.get(0)).getParentFile();
        boolean encode = new File(command.get(0)).getName().equals("ming-reference");
        if (encode) {
            System.load(new File(root, "libonnxruntime.so").getAbsolutePath());
            System.load(new File(root, "libming-reference.so").getAbsolutePath());
        } else {
            System.load(new File(root, "libming-ort.so").getAbsolutePath());
            String runtime = environment.containsKey("MING_FLOW_QNN") ? "qnn/libonnxruntime-qnn.so" : "libonnxruntime.so";
            select(new File(root, runtime).getCanonicalPath());
            System.load(new File(root, "libming-synthesize.so").getAbsolutePath());
        }
        String[] variables = environment.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        String[] arguments = command.toArray(new String[0]);
        int result = encode ? reference(arguments, variables, log.toString()) : synthesize(arguments, variables, log.toString());
        if (result != 0) throw new IllegalStateException("Ming inference failed (" + result + "); see " + log);
    }
}
