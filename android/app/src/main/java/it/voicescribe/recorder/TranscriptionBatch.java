package it.voicescribe.recorder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** WAV/TXT files are the checkpoint, including after a process restart. */
final class TranscriptionBatch {
    interface Upload { void run(File wav) throws Exception; }
    private final File session;
    private final Upload upload;
    private boolean blocked;
    private String error = "";

    TranscriptionBatch(File session, Upload upload) { this.session = session; this.upload = upload; }

    boolean process(File wav) {
        if (Transcriber.result(wav).isFile()) return true;
        if (blocked && !Transcriber.hasCachedResponse(wav)) return false;
        try {
            upload.run(wav);
            if (!Transcriber.result(wav).isFile()) throw new IOException("Trascrizione non salvata");
            return true;
        } catch (Exception e) {
            // A failed provider must not turn a two-hour recording into hours of
            // repeated timeouts. Keep all remaining WAVs on disk for explicit retry.
            blocked = true;
            error = e.getMessage() == null ? "Trascrizione non riuscita" : e.getMessage();
            try (FileOutputStream out = new FileOutputStream(errorFile(session))) {
                out.write(error.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            } catch (IOException ignored) { /* The original WAVs remain recoverable. */ }
            return false;
        }
    }

    String finish() {
        int total = 0, done = 0, speech = 0;
        for (File wav : Transcriber.segments(session)) {
            total++;
            if (Transcriber.result(wav).isFile()) {
                done++;
                if (Transcriber.result(wav).length() > 0) speech++;
            }
        }
        if (done == total) {
            errorFile(session).delete();
            return speech == 0 ? "Audio salvato · nessun parlato riconosciuto"
                : "Sessione salvata · trascritti " + done + "/" + total + " segmenti";
        }
        return "Audio salvato · trascritti " + done + "/" + total + " · " + (total - done)
            + " da riprovare" + (error.isEmpty() ? "" : " · " + error);
    }

    static File errorFile(File session) { return new File(session, "transcription-error.txt"); }
    static String lastError(File session) {
        try { return new String(Files.readAllBytes(errorFile(session).toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
    }
}
