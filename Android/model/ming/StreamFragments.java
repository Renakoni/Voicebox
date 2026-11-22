package dev.ming.local;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Converts completed native fragments while the inference thread produces the next fragment. */
final class StreamFragments implements AutoCloseable {
    private final Thread publisher;
    private Exception failure;
    private volatile boolean nativeFinished;

    StreamFragments(Path directory, List<Path> parts, int startIndex) {
        publisher = new Thread(() -> {
            try {
                for (int i = 0; i < parts.size(); i++) {
                    Path part = parts.get(i);
                    while (true) {
                        boolean finished = nativeFinished;
                        if (Files.isRegularFile(part)) break;
                        if (finished) throw new IOException("Missing Ming fragment: " + part.getFileName());
                        Thread.sleep(20);
                    }
                    publish(part, directory, startIndex + i);
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                failure = error;
                try {
                    Files.write(directory.resolve("abort"), new byte[0]);
                } catch (IOException signalError) {
                    error.addSuppressed(signalError);
                }
            }
        }, "Ming fragment publisher");
        publisher.start();
    }

    void finish() throws Exception {
        nativeFinished = true;
        publisher.join();
        if (failure != null) throw failure;
    }

    @Override public void close() throws InterruptedException {
        publisher.interrupt();
        publisher.join();
    }

    private static Path clip(Path directory, int index) {
        return directory.resolve(index + ".pcm");
    }

    private static void publish(Path part, Path directory, int index) throws IOException {
        SilenceTrim.trim(part);
        byte[] bytes = Files.readAllBytes(part);
        if (bytes.length == 0 || bytes.length % Float.BYTES != 0) throw new IOException("Invalid float PCM");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocate(bytes.length / 2).order(ByteOrder.LITTLE_ENDIAN);
        while (input.hasRemaining()) {
            float value = input.getFloat();
            if (!Float.isFinite(value)) throw new IOException("Nonfinite PCM");
            output.putShort((short) Math.rint(Math.max(-32768, Math.min(32767, value * 32768))));
        }
        Path partial = directory.resolve(index + ".partial");
        Files.write(partial, output.array());
        Files.move(partial, clip(directory, index), StandardCopyOption.ATOMIC_MOVE);
    }

    static double concatenate(Path directory, int count, Path output) throws Exception {
        long bytes = 0;
        for (int i = 0; i < count; i++) bytes += Files.size(clip(directory, i));
        TaskRequest.require(bytes > 0 && bytes < 0xffffffffL - 36, "Invalid WAV size");
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int)(36 + bytes));
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1);
        header.putInt(44100).putInt(88200).putShort((short)2).putShort((short)16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int)bytes);
        try (OutputStream wav = new BufferedOutputStream(Files.newOutputStream(output.resolve("output.wav")))) {
            wav.write(header.array());
            for (int i = 0; i < count; i++) Files.copy(clip(directory, i), wav);
        }
        return bytes / 88200.0;
    }
}
