package it.voicescribe.recorder;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/** Files are the durable queue: a WAV without a matching TXT needs transcription. */
final class Transcriber {
    static File[] segments(File session) {
        File[] files = session == null ? null : session.listFiles((dir, name) -> name.endsWith(".wav"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }
    static File result(File wav) { return new File(wav.getPath() + ".txt"); }
    static String transcript(File session) throws IOException {
        StringBuilder text = new StringBuilder();
        for (File wav : segments(session)) {
            if (result(wav).exists()) {
                long seconds = Long.parseLong(wav.getName().replace(".wav", "")) / 1000;
                text.append(String.format(java.util.Locale.ROOT, "[%02d:%02d] ", seconds / 60, seconds % 60));
                text.append(new String(Files.readAllBytes(result(wav).toPath()), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return text.toString();
    }
    static void transcribe(File wav, String key, String model, String language) throws Exception {
        if (result(wav).exists()) return;
        for (int attempt = 0; ; attempt++) {
            try { upload(wav, key, model, language); return; }
            catch (Retryable e) { if (attempt == 2) throw e; Thread.sleep(1500L * (attempt + 1)); }
        }
    }
    private static void upload(File wav, String key, String model, String language) throws Exception {
        String boundary = "VoiceScribe" + java.util.UUID.randomUUID();
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection();
        conn.setConnectTimeout(15000); conn.setReadTimeout(90000);
        conn.setRequestMethod("POST"); conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setChunkedStreamingMode(8192);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                field(out, boundary, "model", model); field(out, boundary, "language", language);
                field(out, boundary, "response_format", "json"); field(out, boundary, "temperature", "0");
                bytes(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
                Files.copy(wav.toPath(), out); bytes(out, "\r\n--" + boundary + "--\r\n");
            }
            int code = conn.getResponseCode();
            if (code == 429 || code >= 500) throw new Retryable("Groq HTTP " + code);
            if (code < 200 || code >= 300) throw new IOException("Groq HTTP " + code + ": controlla chiave e modello");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = conn.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = input.read(buf)) != -1) body.write(buf, 0, n);
            }
            JSONObject json = new JSONObject(body.toString("UTF-8"));
            if (!json.has("text")) throw new IOException("Risposta Groq senza testo");
            File tmp = new File(result(wav).getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getString("text").trim().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            if (!tmp.renameTo(result(wav))) throw new IOException("Salvataggio trascrizione fallito");
        } catch (SocketTimeoutException | ConnectException | UnknownHostException e) {
            throw new Retryable("Rete non disponibile o timeout");
        } finally { conn.disconnect(); }
    }
    private static void field(OutputStream out, String b, String name, String value) throws IOException {
        bytes(out, "--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void bytes(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.UTF_8)); }
    private static final class Retryable extends IOException {
        private static final long serialVersionUID = 1L;
        Retryable(String message) { super(message); }
    }
}
