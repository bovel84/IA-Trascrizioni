package it.voicescribe.recorder;

import java.io.*;

/** PCM mono, 16 bit, 16 kHz. The header is repaired after an interrupted process. */
final class WavFile implements Closeable {
    static final int RATE = 16000;
    static final int BYTES_PER_SECOND = RATE * 2;
    private final RandomAccessFile out;
    WavFile(File file) throws IOException {
        out = new RandomAccessFile(file, "rw");
        out.setLength(0);
        out.write(new byte[44]);
    }
    void write(byte[] data, int count) throws IOException { out.write(data, 0, count); }
    public void close() throws IOException {
        try { header(out); out.getFD().sync(); } finally { out.close(); }
    }
    static void repair(File file) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            if (out.length() < 44) throw new IOException("Segmento incompleto: " + file.getName());
            // Drop an incomplete final PCM sample.
            out.setLength(44 + ((out.length() - 44) / 2) * 2);
            header(out);
            out.getFD().sync();
        }
    }
    private static void header(RandomAccessFile out) throws IOException {
        int size = (int) (out.length() - 44);
        out.seek(0); out.writeBytes("RIFF"); le32(out, size + 36); out.writeBytes("WAVEfmt ");
        le32(out, 16); le16(out, 1); le16(out, 1); le32(out, RATE);
        le32(out, BYTES_PER_SECOND); le16(out, 2); le16(out, 16);
        out.writeBytes("data"); le32(out, size);
    }
    private static void le16(RandomAccessFile out, int n) throws IOException {
        out.write(n); out.write(n >>> 8);
    }
    private static void le32(RandomAccessFile out, int n) throws IOException {
        le16(out, n); le16(out, n >>> 16);
    }
}
