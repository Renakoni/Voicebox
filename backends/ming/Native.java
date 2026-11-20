package dev.ming.local;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** JNI glue only. Task parsing, BPE and inference remain owned by ming-android. */
final class Native {
    private static boolean loaded;
    private static native int synthesize(String[] arguments, String[] environment, String log);
    private static native int reference(String[] arguments, String[] environment, String log);

    static void process(List<String> command, Map<String, String> environment, Path log) throws Exception {
        File root = new File(command.get(0)).getParentFile();
        if (!loaded) {
            System.load(new File(root, "libonnxruntime.so").getAbsolutePath());
            System.load(new File(root, "libming-synthesize.so").getAbsolutePath());
            System.load(new File(root, "libming-reference.so").getAbsolutePath());
            loaded = true;
        }
        String[] variables = environment.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        String[] arguments = command.toArray(new String[0]);
        int result = new File(command.get(0)).getName().equals("ming-reference")
                ? reference(arguments, variables, log.toString()) : synthesize(arguments, variables, log.toString());
        if (result != 0) throw new IllegalStateException("Ming inference failed (" + result + "); see " + log);
    }
}
