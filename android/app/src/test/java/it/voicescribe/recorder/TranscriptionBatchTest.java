package it.voicescribe.recorder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TranscriptionBatchTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File twoHours() throws Exception {
        File session = temp.newFolder();
        for (int i = 0; i < 240; i++) {
            File file = new File(session, String.format(java.util.Locale.ROOT, "%012d.wav", i * 30000L));
            // Sparse PCM files have the real duration without filling the test disk.
            try (RandomAccessFile wav = new RandomAccessFile(file, "rw")) { wav.setLength(44 + 30L * WavFile.BYTES_PER_SECOND); }
            WavFile.repair(file);
        }
        return session;
    }
    private void writeResult(File wav, String text) throws Exception {
        Files.write(Transcriber.result(wav).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void twoHoursAreCheckpointedAndNeverUploadedAgain() throws Exception {
        File session = twoHours(); AtomicInteger calls = new AtomicInteger();
        TranscriptionBatch batch = new TranscriptionBatch(session, wav -> { calls.incrementAndGet(); writeResult(wav, "Trascrizione"); });
        long pcmBytes = 0;
        for (File wav : Transcriber.segments(session)) { assertTrue(batch.process(wav)); pcmBytes += wav.length() - 44; }
        assertEquals(7200L * WavFile.BYTES_PER_SECOND, pcmBytes);
        assertEquals(240, calls.get()); assertTrue(batch.finish().contains("240/240"));
        TranscriptionBatch resumed = new TranscriptionBatch(session, wav -> fail("Completed audio must not be uploaded"));
        for (File wav : Transcriber.segments(session)) assertTrue(resumed.process(wav));
    }

    @Test public void FailureHalfwayStopsTimeoutStormAndResumesOnlyMissingAudio() throws Exception {
        File session = twoHours(); AtomicInteger calls = new AtomicInteger();
        TranscriptionBatch batch = new TranscriptionBatch(session, wav -> {
            if (calls.incrementAndGet() == 121) throw new IOException("Timeout rete");
            writeResult(wav, "Prima parte");
        });
        for (File wav : Transcriber.segments(session)) batch.process(wav);
        assertEquals(121, calls.get()); assertEquals(240, Transcriber.segments(session).length);
        assertTrue(batch.finish().contains("120/240")); assertEquals("Timeout rete", TranscriptionBatch.lastError(session));
        AtomicInteger remaining = new AtomicInteger();
        TranscriptionBatch resumed = new TranscriptionBatch(session, wav -> { remaining.incrementAndGet(); writeResult(wav, "Recuperata"); });
        for (File wav : Transcriber.segments(session)) assertTrue(resumed.process(wav));
        assertEquals(120, remaining.get()); assertTrue(resumed.finish().contains("240/240"));
        assertEquals("", TranscriptionBatch.lastError(session));
        assertTrue(new String(Files.readAllBytes(Transcriber.result(Transcriber.segments(session)[0]).toPath()), StandardCharsets.UTF_8).equals("Prima parte"));
    }

    @Test public void SilenceIsDifferentFromAnUnwrittenResult() throws Exception {
        File session = temp.newFolder(); File wav = new File(session, "000000000000.wav");
        try (WavFile out = new WavFile(wav)) { out.write(new byte[320], 320); }
        TranscriptionBatch missing = new TranscriptionBatch(session, file -> {});
        assertFalse(missing.process(wav)); assertTrue(missing.finish().contains("0/1"));
        TranscriptionBatch silence = new TranscriptionBatch(session, file -> writeResult(file, ""));
        assertTrue(silence.process(wav)); assertTrue(silence.finish().contains("nessun parlato"));
    }
}
