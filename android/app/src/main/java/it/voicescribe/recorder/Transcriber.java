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
    interface ConnectionFactory { HttpURLConnection open() throws IOException; }
    interface Wait { void sleep(long millis) throws InterruptedException; }
    static File[] segments(File session) {
        File[] files = session == null ? null : session.listFiles((dir, name) -> name.endsWith(".wav"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }
    static File result(File wav) { return new File(wav.getPath() + ".txt"); }
    static boolean hasCachedResponse(File wav){try{return VoiceData.read(VoiceData.response(wav)).opt("text") instanceof String;}catch(Exception invalid){return false;}}
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
        transcribe(wav, key, model, language, () -> (HttpURLConnection) new URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection(), Thread::sleep);
    }
    static void transcribe(File wav, String key, String model, String language, ConnectionFactory factory, Wait wait) throws Exception {
        if(result(wav).exists())return;
        if (VoiceData.response(wav).exists()) {
            JSONObject cached=null;
            try{cached=VoiceData.read(VoiceData.response(wav));if(!(cached.opt("text") instanceof String))cached=null;}
            catch(Exception invalid){ /* Recover an unreadable response from the original WAV. */ }
            if(cached!=null){saveResponse(wav,cached);return;}
        }
        // Keep silence in the exported WAV/timeline, but never ask Whisper to invent text.
        // Also applies to retry/recovery after a process interruption.
        if (!WavFile.hasSignal(wav)) { Files.write(result(wav).toPath(), new byte[0]); return; }
        for (int attempt = 0; ; attempt++) {
            try { upload(wav, key, model, language, factory.open()); return; }
            catch (Retryable e) {
                // Long quota windows are recoverable work, not a reason to block
                // hundreds of subsequent segments or ignore Retry-After.
                if (attempt == 2 || e.delayMs > 10000) throw e;
                wait.sleep(Math.max(e.delayMs, 1500L * (attempt + 1)));
            }
        }
    }
    private static void upload(File wav, String key, String model, String language, HttpURLConnection conn) throws Exception {
        String boundary = "VoiceScribe" + java.util.UUID.randomUUID();
        conn.setConnectTimeout(15000); conn.setReadTimeout(90000);
        conn.setRequestMethod("POST"); conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        // Groq and some reverse proxies reject multipart uploads with chunked transfer encoding.
        // The WAV is already stored on disk: stream it with a known Content-Length, without buffering the audio.
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        field(prefix, boundary, "model", model); field(prefix, boundary, "language", language);
        String context=contextPrompt(wav);if(!context.isEmpty())field(prefix,boundary,"prompt",context);
        boolean nativeVoices=wantsVoices(wav);
        field(prefix, boundary, "response_format", "verbose_json");
        field(prefix,boundary,"timestamp_granularities[]","segment");
        if(nativeVoices)field(prefix,boundary,"timestamp_granularities[]","word");
        field(prefix, boundary, "temperature", "0");
        bytes(prefix, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n");
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode((long) prefix.size() + wav.length() + suffix.length);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                prefix.writeTo(out);
                Files.copy(wav.toPath(), out);
                out.write(suffix);
            }
            int code = conn.getResponseCode();
            if(code==429)throw new QuotaLimited(retryDelayMillis(conn.getHeaderField("Retry-After")));
            if (code >= 500) throw new Retryable("Groq HTTP " + code);
            if (code < 200 || code >= 300) {
                String detail = errorDetail(conn);
                if (key != null && !key.isEmpty()) detail = detail.replace(key, "[chiave nascosta]");
                throw new IOException("Groq HTTP " + code + (detail.isEmpty() ? ": controlla chiave, formato e modello" : ": " + detail));
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = conn.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = input.read(buf)) != -1) body.write(buf, 0, n);
            }
            JSONObject json = new JSONObject(body.toString("UTF-8"));
            saveResponse(wav,json);
        } catch (SocketTimeoutException | ConnectException | UnknownHostException e) {
            throw new Retryable("Rete non disponibile o timeout");
        } finally { conn.disconnect(); }
    }
    private static boolean wantsVoices(File wav){return VoiceData.enabled(wav.getParentFile())||VoiceData.timeline(wav).exists();}
    static String contextPrompt(File wav){
        String glossary="",previous="";
        try{glossary=VoiceData.read(new File(wav.getParentFile(),"transcription-settings.json")).optString("glossary","");}catch(Exception ignored){}
        File prior=null;for(File file:segments(wav.getParentFile())){if(file.getName().compareTo(wav.getName())>=0)break;prior=file;}
        try{if(prior!=null&&result(prior).isFile()){
            long start=Long.parseLong(wav.getName().replace(".wav","")),end=Long.parseLong(prior.getName().replace(".wav",""))+VoiceData.duration(prior);
            boolean suspect=VoiceData.readQuality(prior).optBoolean("review",false);
            if(!suspect&&Math.abs(start-end)<=500)previous=new String(Files.readAllBytes(result(prior).toPath()),StandardCharsets.UTF_8);
        }}catch(Exception ignored){}
        return SpeechContext.prompt(glossary,previous);
    }
    static void saveResponse(File wav,JSONObject json)throws Exception{
        if(!(json.opt("text") instanceof String))throw new IOException("Risposta Groq senza testo");
        String text=json.getString("text").trim();
        // Fsync the full text before optional attribution. Publish the TXT after that
        // bounded local step, so polling cannot import a raw chunk followed by duplicate turns.
        File tmp=new File(result(wav).getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        try{VoiceData.write(VoiceData.response(wav),json);}catch(Exception ignored){ /* Full TXT still publishes below. */ }
        try{VoiceData.write(VoiceData.quality(wav),VoiceData.assess(json));}catch(Exception ignored){ /* Quality metadata is optional. */ }
        if(wantsVoices(wav)){
            try{VoiceData.align(wav,json);}catch(Exception ignored){ /* UI falls back to the entire TXT, unassigned. */ }
        }
        if(!tmp.renameTo(result(wav)))throw new IOException("Salvataggio trascrizione fallito");
    }
    private static String errorDetail(HttpURLConnection conn) {
        try (InputStream stream = conn.getErrorStream()) {
            if (stream == null) return "";
            byte[] buffer = new byte[4096];
            int length = stream.read(buffer);
            if (length <= 0) return "";
            JSONObject response = new JSONObject(new String(buffer, 0, length, StandardCharsets.UTF_8));
            JSONObject error = response.optJSONObject("error");
            String detail = error == null ? response.optString("message", "") : error.optString("message", "");
            return detail.length() > 220 ? detail.substring(0, 220) : detail;
        } catch (Exception ignored) { return ""; }
    }
    private static void field(OutputStream out, String b, String name, String value) throws IOException {
        bytes(out, "--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void bytes(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.UTF_8)); }
    static long retryDelayMillis(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (Double.isFinite(seconds) && seconds >= 0) return (long) Math.min(86400000, Math.ceil(seconds * 1000));
        } catch (Exception ignored) { }
        return 60000; // Unknown quota window: leave durable work for manual retry.
    }
    private static class Retryable extends IOException {
        private static final long serialVersionUID = 1L;
        final long delayMs;
        Retryable(String message) { this(message, 0); }
        Retryable(String message, long delayMs) { super(message); this.delayMs = delayMs; }
    }
    static final class QuotaLimited extends Retryable {
        private static final long serialVersionUID=1L;
        QuotaLimited(){this(60000);}
        QuotaLimited(long delay){super("Limite Groq raggiunto (HTTP 429) · riprova dopo "+Math.max(1,(delay+999)/1000)+" s",delay);}
    }
}
