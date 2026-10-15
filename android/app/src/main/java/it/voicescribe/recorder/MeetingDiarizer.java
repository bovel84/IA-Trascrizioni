package it.voicescribe.recorder;

import java.io.*;
import java.net.*;
import java.nio.file.Files;

/** One audio upload per explicit user action; never retry a billable request silently. */
final class MeetingDiarizer {
    static String endpoint(String language) throws IOException {
        if (language == null || !language.matches("[a-z]{2}")) throw new IOException("Lingua non valida");
        return "https://api.deepgram.com/v1/listen?model=nova-3&diarize_model=v2&utterances=true&smart_format=true&language=" + language;
    }
    static String analyse(File audio, String key, String language) throws IOException {
        if (key == null || !key.trim().matches("[A-Za-z0-9._-]+")) throw new IOException("Chiave Deepgram mancante");
        if (audio.length() <= 44 || audio.length() > 2_000_000_000L) throw new IOException("Audio vuoto o superiore a 2 GB");
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint(language)).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000); conn.setReadTimeout(660000);
        conn.setRequestMethod("POST"); conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Token " + key.trim());
        conn.setRequestProperty("Content-Type", "audio/wav");
        conn.setFixedLengthStreamingMode(audio.length());
        try {
            try (OutputStream out = conn.getOutputStream()) { Files.copy(audio.toPath(), out); }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Deepgram HTTP " + status + ": controlla chiave, credito e formato audio");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (body.size() + n > 24 * 1024 * 1024) throw new IOException("Risposta troppo grande: originale conservato");
                    body.write(buffer, 0, n);
                }
            }
            return body.toString("UTF-8");
        } catch (SocketTimeoutException e) { throw new IOException("Timeout Deepgram: originale conservato; nessun reinvio automatico"); }
        finally { conn.disconnect(); }
    }
}
