package it.voicescribe.recorder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Only the configured AI endpoints; never accepts a caller supplied URL. */
final class AiHttp {
    static String endpoint(String provider, String path) throws IOException {
        if (!"/models".equals(path) && !"/chat/completions".equals(path)) throw new IOException("Operazione AI non consentita");
        String base;
        switch (provider) {
            case "groq": base = "https://api.groq.com/openai/v1"; break;
            case "ollama":
                if ("/models".equals(path)) return "https://ollama.com/api/tags";
                base = "https://ollama.com/v1"; break;
            case "nvidia": base = "https://integrate.api.nvidia.com/v1"; break;
            case "openrouter": base = "https://openrouter.ai/api/v1"; break;
            default: throw new IOException("Provider AI non valido");
        }
        return base + path;
    }
    static final class Result {
        final int status; final String body;
        Result(int status, String body) { this.status = status; this.body = body; }
    }
    static Result request(String provider, String path, String key, String body) throws IOException {
        String endpoint = endpoint(provider, path);
        if (key.isEmpty() || key.length() > 4096 || key.contains("\r") || key.contains("\n") || body.length() > 8 * 1024 * 1024)
            throw new IOException("Chiave o richiesta AI non valida");
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000); conn.setReadTimeout(90000);
        conn.setRequestProperty("Authorization", "Bearer " + key);
        try {
            if ("/chat/completions".equals(path)) {
                conn.setRequestMethod("POST"); conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (stream != null) try (InputStream input = stream) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 8 * 1024 * 1024) throw new IOException("Risposta AI troppo grande");
                    output.write(buffer, 0, count);
                }
            }
            return new Result(status, new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally { conn.disconnect(); }
    }
}
