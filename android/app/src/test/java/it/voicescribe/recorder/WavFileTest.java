package it.voicescribe.recorder;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;

public class WavFileTest {
    private int le32(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset+1] & 255) << 8) | ((data[offset+2] & 255) << 16) | ((data[offset+3] & 255) << 24);
    }
    @Test public void writesPlayableHeaderAndPreservesSamples() throws Exception {
        File file = File.createTempFile("voicescribe", ".wav");
        try {
            byte[] pcm = { 1, 2, 3, 4, 5, 6 };
            try (WavFile wav = new WavFile(file)) { wav.write(pcm, pcm.length); }
            byte[] data = Files.readAllBytes(file.toPath());
            assertEquals("RIFF", new String(data, 0, 4, "US-ASCII"));
            assertEquals(42, le32(data, 4)); assertEquals(16000, le32(data, 24));
            assertEquals(32000, le32(data, 28)); assertEquals(6, le32(data, 40));
            assertArrayEquals(pcm, Arrays.copyOfRange(data, 44, data.length));
        } finally { file.delete(); }
    }
    @Test public void repairsHeaderAfterProcessTerminationAndDropsHalfSample() throws Exception {
        File file = File.createTempFile("voicescribe", ".part");
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[44]); out.write(new byte[] { 1, 2, 3, 4, 5 }); }
            WavFile.repair(file);
            byte[] data = Files.readAllBytes(file.toPath());
            assertEquals(48, data.length); assertEquals(4, le32(data, 40));
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, Arrays.copyOfRange(data, 44, data.length));
        } finally { file.delete(); }
    }
    @Test public void rejectsPartialFilesWithoutHeader() throws Exception {
        File file = File.createTempFile("voicescribe", ".part");
        try { assertThrows(IOException.class, () -> WavFile.repair(file)); }
        finally { file.delete(); }
    }
}
