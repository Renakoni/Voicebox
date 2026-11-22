package dev.ming.local;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Matches the official 44.1 kHz mono postprocessing on unquantized samples. */
final class SilenceTrim {
    private static final int FRAME_SAMPLES = 4410;
    private static final int FRAME_BYTES = FRAME_SAMPLES * Float.BYTES;
    private static final int TAIL_FRAMES = 3;
    private static final float THRESHOLD = 0.001f;

    static void trim(Path pcm) throws IOException {
        try (FileChannel file = FileChannel.open(pcm, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long bytes = file.size();
            if (bytes % Float.BYTES != 0) throw new IOException("Invalid float PCM");
            long frames = bytes / FRAME_BYTES;
            if (frames == 0) return;

            ByteBuffer buffer = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            long lastAudible = frames - 1;
            for (; lastAudible >= 0; lastAudible--) {
                file.position(lastAudible * FRAME_BYTES);
                buffer.clear();
                while (buffer.hasRemaining()) {
                    if (file.read(buffer) < 0) throw new EOFException("Incomplete float PCM frame");
                }
                buffer.flip();
                double magnitude = 0;
                while (buffer.hasRemaining()) {
                    float sample = buffer.getFloat();
                    if (!Float.isFinite(sample)) throw new IOException("Nonfinite PCM");
                    magnitude += Math.abs(sample);
                }
                if ((float) (magnitude / FRAME_SAMPLES) > THRESHOLD) break;
            }
            file.truncate(Math.min(frames, lastAudible + 1 + TAIL_FRAMES) * FRAME_BYTES);
        }
    }
}
