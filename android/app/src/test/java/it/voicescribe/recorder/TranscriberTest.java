package it.voicescribe.recorder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TranscriberTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File wav() throws Exception {
        File file = temp.newFile();
        try (WavFile out = new WavFile(file)) { byte[] pcm = new byte[32000]; Arrays.fill(pcm, (byte) 16); out.write(pcm, pcm.length); }
        return file;
    }
    static final class Reply extends HttpURLConnection {
        final int status; final String body, retryAfter;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean disconnected, timeout;
        Reply(int status, String body, String retryAfter) throws Exception {
            super(new URL("https://api.groq.com/openai/v1/audio/transcriptions"));
            this.status = status; this.body = body; this.retryAfter = retryAfter;
        }
        public void connect() {}
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
        public OutputStream getOutputStream() { return sent; }
        public int getResponseCode() { return status; }
        public String getHeaderField(String name) { return "Retry-After".equals(name) ? retryAfter : null; }
        public InputStream getInputStream() throws IOException {
            if (timeout) throw new SocketTimeoutException();
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
        public InputStream getErrorStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
        long contentLength() { return fixedContentLengthLong; }
    }
    @Test public void SuccessfulUploadUsesFixedLengthAndCommitsText() throws Exception {
        File wav = wav(); Reply response = new Reply(200, "{\"text\":\" Testo completo \"}", null);
        Transcriber.transcribe(wav, "test_key", "whisper-large-v3-turbo", "it", () -> response, millis -> fail("No retry expected"));
        assertEquals(response.sent.size(), response.contentLength()); assertTrue(response.disconnected);
        assertEquals("Testo completo", new String(Files.readAllBytes(Transcriber.result(wav).toPath()), StandardCharsets.UTF_8));
    }
    @Test public void LongQuotaWindowDoesNotRetryImmediately() throws Exception {
        File wav = wav(); Reply response = new Reply(429, "{}", "3600"); AtomicInteger calls = new AtomicInteger();
        try {
            Transcriber.transcribe(wav, "test_key", "whisper-large-v3-turbo", "it", () -> { calls.incrementAndGet(); return response; }, millis -> fail("Must not block for an hour"));
            fail("Quota error expected");
        } catch (IOException e) { assertTrue(e.getMessage().contains("3600")); }
        assertEquals(1, calls.get()); assertFalse(Transcriber.result(wav).exists()); assertTrue(response.disconnected);
    }
    @Test public void ShortQuotaWindowHonoursRetryAfter() throws Exception {
        File wav = wav(); AtomicInteger calls = new AtomicInteger(); List<Long> waits = new ArrayList<>();
        Reply denied = new Reply(429, "{}", "2"), ok = new Reply(200, "{\"text\":\"Ripresa\"}", null);
        Transcriber.transcribe(wav, "test_key", "whisper-large-v3-turbo", "it", () -> calls.incrementAndGet() == 1 ? denied : ok, waits::add);
        assertEquals(2, calls.get()); assertEquals(Arrays.asList(2000L), waits); assertTrue(Transcriber.result(wav).exists());
    }
    @Test public void InvalidCredentialsRemainRecoverableAndRedactKey() throws Exception {
        File wav = wav(); Reply response = new Reply(401, "{\"error\":{\"message\":\"Invalid test_key\"}}", null);
        try {
            Transcriber.transcribe(wav, "test_key", "whisper-large-v3-turbo", "it", () -> response, millis -> fail("Invalid credentials must not retry"));
            fail("Authentication error expected");
        } catch (IOException e) { assertTrue(e.getMessage().contains("401")); assertFalse(e.getMessage().contains("test_key")); }
        assertFalse(Transcriber.result(wav).exists());
    }
    @Test public void MalformedSuccessCannotCreateACompletedCheckpoint() throws Exception {
        for (String body : Arrays.asList("{}", "{\"text\":null}", "{\"text\":42}")) {
            File wav = wav(); Reply response = new Reply(200, body, null);
            try {
                Transcriber.transcribe(wav, "test_key", "whisper-large-v3-turbo", "it", () -> response, millis -> fail("No retry expected"));
                fail("Invalid response expected");
            } catch (IOException e) { assertTrue(e.getMessage().contains("senza testo")); }
            assertFalse(Transcriber.result(wav).exists()); assertTrue(wav.exists());
        }
    }
    @Test public void ResponseTimeoutHasBoundedAttempts() throws Exception {
        File wav = wav(); AtomicInteger calls = new AtomicInteger(); List<Long> waits = new ArrayList<>();
        try {
            Transcriber.transcribe(wav, "test_key", "whisper-large-v3-turbo", "it", () -> {
                calls.incrementAndGet(); Reply response;
                try { response = new Reply(200, "", null); } catch (Exception e) { throw new IOException(e); }
                response.timeout = true; return response;
            }, waits::add);
            fail("Timeout expected");
        } catch (IOException e) { assertTrue(e.getMessage().contains("timeout")); }
        assertEquals(3, calls.get()); assertEquals(Arrays.asList(1500L, 3000L), waits); assertFalse(Transcriber.result(wav).exists());
    }
    @Test public void RetryAfterParsingIsBounded() {
        assertEquals(2500L, Transcriber.retryDelayMillis("2.5"));
        assertEquals(60000L, Transcriber.retryDelayMillis(null));
        assertEquals(60000L, Transcriber.retryDelayMillis("NaN"));
        assertEquals(60000L, Transcriber.retryDelayMillis("-1"));
        assertEquals(86400000L, Transcriber.retryDelayMillis("9999999999999"));
    }
}
