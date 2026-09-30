package it.voicescribe.recorder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Original VoiceScribe UI; the microphone belongs exclusively to the native service. */
public final class MainActivity extends Activity {
    private static final String PAGE = "https://appassets.androidplatform.net/assets/index.html";
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService aiWorker = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(4));
    private volatile boolean starting, retrying;
    private String key, model, language;
    private boolean pendingStart;
    private ValueCallback<Uri[]> filePicker;
    private File download;
    private final Deque<Download> downloads = new ArrayDeque<>();
    private boolean exporting;

    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        web = new WebView(this); web.setBackgroundColor(0xff0a0a0f); setContentView(web);
        web.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new RecorderBridge(), "AndroidRecorder");
        web.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (PAGE.equals(request.getUrl().toString())) return false;
                if (request.isForMainFrame() && "https".equals(request.getUrl().getScheme()))
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                return true;
            }
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!"https".equals(url.getScheme()) || !"appassets.androidplatform.net".equals(url.getHost())) return null;
                String path = url.getPath();
                try {
                    if (path != null && path.matches("/assets/(index\\.html|native-bridge\\.js|icon\\.svg|manifest\\.webmanifest|vendor/(jspdf\\.umd\\.min\\.js|chart\\.umd\\.js))")) {
                        String type = path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "application/javascript" : path.endsWith(".svg") ? "image/svg+xml" : "application/json";
                        Map<String,String> headers = new HashMap<>();
                        headers.put("Content-Security-Policy", "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; connect-src 'self' data: blob: https://api.groq.com; img-src 'self' data: blob:; media-src 'self' data: blob:; frame-src 'none'; object-src 'none'; base-uri 'none'");
                        return new WebResourceResponse(type, "UTF-8", 200, "OK", headers, getAssets().open(path.substring(8)));
                    }
                } catch (IOException ignored) { }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("OK", (d,w) -> result.confirm()).setOnCancelListener(d -> result.cancel()).show(); return true;
            }
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("Sì", (d,w) -> result.confirm()).setNegativeButton("No", (d,w) -> result.cancel()).setOnCancelListener(d -> result.cancel()).show(); return true;
            }
            public boolean onJsPrompt(WebView view, String url, String message, String value, JsPromptResult result) {
                android.widget.EditText input = new android.widget.EditText(MainActivity.this); input.setText(value);
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setView(input).setPositiveButton("OK", (d,w) -> result.confirm(input.getText().toString())).setNegativeButton("Annulla", (d,w) -> result.cancel()).setOnCancelListener(d -> result.cancel()).show(); return true;
            }
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePicker != null) filePicker.onReceiveValue(null);
                filePicker = callback;
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), 10); return true;
            }
        });
        web.loadUrl(PAGE);
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void startError(String message) {
        starting = false; pendingStart = false; RecordingService.status = message;
        if (web != null) web.evaluateJavascript("window.nativeRecorderError && window.nativeRecorderError(" + JSONObject.quote(message) + ")", null);
    }
    private void requestStart(String apiKey, String selectedModel, String lang) {
        if (RecordingService.active || starting || retrying) return;
        if (!hasWindowFocus() || isFinishing()) { startError("Torna nell'app per avviare il microfono"); return; }
        if (apiKey.trim().isEmpty() || selectedModel.trim().isEmpty() || !lang.matches("[a-z]{2}")) { startError("Inserisci chiave Whisper, modello e lingua"); return; }
        key = apiKey; model = selectedModel; language = lang; starting = true;
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) { requestPermissions(permissions.toArray(new String[0]), 20); return; }
        begin();
    }
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 20) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { startError("Permesso microfono negato"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            toast("Notifiche negate: usa Ferma nell'app; servizio visibile in App attive");
        pendingStart = true;
        if (hasWindowFocus()) { pendingStart = false; begin(); }
    }
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && pendingStart) { pendingStart = false; begin(); }
    }
    private void begin() {
        if (!hasWindowFocus() || isFinishing()) { startError("Torna nell'app e premi Registra"); return; }
        try {
            startForegroundService(new Intent(this, RecordingService.class).putExtra("key", key).putExtra("model", model).putExtra("language", language));
            ui.postDelayed(() -> { starting = false; key = null; }, 400);
        } catch (Exception e) { startError("Avvio non consentito: " + e.getMessage()); }
    }
    private File session(String id) throws IOException {
        if (id == null || !id.matches("session-[0-9]{13}")) throw new IOException("Sessione non valida");
        File dir = new File(getFilesDir(), id);
        if (!dir.isDirectory()) throw new IOException("Sessione non trovata");
        return dir;
    }
    private String latest() { return getSharedPreferences("native", 0).getString("latest", ""); }
    private JSONObject snapshot(String id) throws Exception {
        File dir = session(id); JSONArray entries = new JSONArray(); long size = 0;
        int pending = 0; File[] files = Transcriber.segments(dir);
        for (File wav : files) {
            size += Math.max(0, wav.length() - 44);
            if (Transcriber.result(wav).exists()) {
                String text = new String(Files.readAllBytes(Transcriber.result(wav).toPath()), StandardCharsets.UTF_8);
                entries.put(new JSONObject().put("nativeSegmentId", id + "/" + wav.getName()).put("offsetMs", Long.parseLong(wav.getName().replace(".wav", ""))).put("text", text));
            } else pending++;
        }
        File[] partials = dir.listFiles((d,n) -> n.endsWith(".part"));
        if (partials != null) for (File partial : partials) size += Math.max(0, partial.length() - 44);
        return new JSONObject().put("sessionId", id).put("entries", entries).put("elapsedMs", size * 1000 / WavFile.BYTES_PER_SECOND).put("chunkCount", files.length).put("pending", pending);
    }
    private void repair(File dir) throws IOException {
        if (RecordingService.active && dir.getName().equals(latest())) return;
        File[] files = dir.listFiles((d,n) -> n.endsWith(".part"));
        if (files != null) for (File file : files) {
            WavFile.repair(file);
            if (!file.renameTo(new File(file.getPath().replace(".part", ".wav")))) throw new IOException("Recupero audio fallito");
        }
    }
    private File combinedAudio(String id) throws IOException {
        File dir = session(id); repair(dir); File[] files = Transcriber.segments(dir);
        if (files.length == 0) throw new IOException("Attendi il primo segmento o ferma la registrazione");
        File out = File.createTempFile("voicescribe-", ".wav", getCacheDir());
        try (WavFile wav = new WavFile(out)) {
            byte[] buffer = new byte[8192];
            for (File file : files) try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(44); int n; while ((n = input.read(buffer)) > 0) wav.write(buffer, n);
            }
        }
        return out;
    }
    private static final class Download {
        final File file; final String name, type;
        Download(File file, String name, String type) { this.file = file; this.name = name; this.type = type; }
    }
    private void offerDownload(File file, String name, String type) {
        downloads.add(new Download(file, name, type)); if (!exporting) nextDownload();
    }
    private void nextDownload() {
        Download next = downloads.poll(); if (next == null) { exporting = false; return; }
        exporting = true; download = next.file;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(next.type).putExtra(Intent.EXTRA_TITLE, next.name), 30);
    }
    public final class RecorderBridge {
        @JavascriptInterface public void aiRequest(String id, String provider, String path, String apiKey, String body) {
            if (!id.matches("[0-9]{1,12}")) return;
            Runnable request = () -> {
                try {
                    AiHttp.Result result = AiHttp.request(provider, path, apiKey, body);
                    aiReply(id, result.status, result.body, "");
                } catch (Exception e) { aiReply(id, 0, "", "Richiesta AI non riuscita: controlla connessione, chiave e modello."); }
            };
            try { aiWorker.execute(request); }
            catch (RejectedExecutionException e) { aiReply(id, 0, "", "Troppe richieste AI: attendi e riprova."); }
        }
        @JavascriptInterface public void start(String apiKey, String selectedModel, String lang) { ui.post(() -> requestStart(apiKey, selectedModel, lang)); }
        @JavascriptInterface public void stop() { ui.post(() -> { if (RecordingService.active) startService(new Intent(MainActivity.this, RecordingService.class).setAction(RecordingService.STOP)); }); }
        @JavascriptInterface public void pause(boolean value) { if (RecordingService.recording) RecordingService.paused = value; }
        @JavascriptInterface public String state() {
            try {
                String id = latest(); JSONObject state = id.isEmpty() ? new JSONObject() : snapshot(id);
                return state.put("active", RecordingService.active).put("recording", RecordingService.recording).put("paused", RecordingService.paused)
                    .put("starting", starting).put("retrying", retrying).put("level", RecordingService.level).put("status", RecordingService.status).toString();
            } catch (Exception e) { return "{\"active\":false,\"recording\":false,\"status\":\"Lettura sessione fallita\"}"; }
        }
        @JavascriptInterface public String sessions() {
            JSONArray data = new JSONArray(); File[] dirs = getFilesDir().listFiles(f -> f.isDirectory() && f.getName().matches("session-[0-9]{13}"));
            if (dirs != null) { Arrays.sort(dirs, Comparator.comparing(File::getName).reversed()); for (File dir : dirs) try { data.put(snapshot(dir.getName())); } catch (Exception ignored) { } }
            return data.toString();
        }
        @JavascriptInterface public void retry(String id, String apiKey, String selectedModel, String lang) {
            ui.post(() -> {
                if (RecordingService.active || retrying || !hasWindowFocus()) return;
                retrying = true;
                worker.execute(() -> {
                    try {
                        File dir = session(id); repair(dir);
                        for (File wav : Transcriber.segments(dir)) Transcriber.transcribe(wav, apiKey, selectedModel, lang);
                        RecordingService.status = "Sessione recuperata e trascritta";
                    } catch (Exception e) { RecordingService.status = "Audio conservato · " + e.getMessage(); }
                    finally { retrying = false; }
                });
            });
        }
        @JavascriptInterface public void batterySettings() { ui.post(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))); }
        @JavascriptInterface public void exportAudio(String id) {
            worker.execute(() -> {
                try { File file = combinedAudio(id); ui.post(() -> offerDownload(file, "voicescribe_" + id + ".wav", "audio/wav")); }
                catch (Exception e) { ui.post(() -> toast(e.getMessage())); }
            });
        }
        @JavascriptInterface public void download(String name, String type, String base64) {
            if (base64.length() > 64 * 1024 * 1024) { ui.post(() -> toast("Export troppo grande")); return; }
            worker.execute(() -> {
                try {
                    byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    File file = File.createTempFile("export-", ".tmp", getCacheDir()); Files.write(file.toPath(), data);
                    String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
                    ui.post(() -> offerDownload(file, safeName, type.isEmpty() ? "application/octet-stream" : type));
                } catch (Exception e) { ui.post(() -> toast("Export fallito: " + e.getMessage())); }
            });
        }
    }
    private void aiReply(String id, int status, String body, String error) {
        ui.post(() -> {
            if (web != null) web.evaluateJavascript("window.nativeAiReply && window.nativeAiReply(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body) + "," + JSONObject.quote(error) + ")", null);
        });
    }
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10 && filePicker != null) { filePicker.onReceiveValue(result == RESULT_OK && data != null && data.getData() != null ? new Uri[] { data.getData() } : null); filePicker = null; }
        if (request == 30) {
            File file = download; download = null;
            if (result == RESULT_OK && data != null && data.getData() != null && file != null) {
                Uri destination = data.getData();
                worker.execute(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(destination)) { Files.copy(file.toPath(), out); ui.post(() -> toast("File salvato")); }
                    catch (Exception e) { ui.post(() -> toast("Salvataggio fallito: " + e.getMessage())); }
                    finally { file.delete(); }
                });
            } else if (file != null) file.delete();
            nextDownload();
        }
    }
    public void onBackPressed() { web.evaluateJavascript("document.querySelectorAll('.modal.active').forEach(m => m.classList.remove('active'))", null); moveTaskToBack(true); }
    protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    protected void onPause() {
        if (web != null) { web.evaluateJavascript("typeof autosaveNow === 'function' && autosaveNow()", null); web.onPause(); }
        super.onPause();
    }
    protected void onDestroy() {
        if (filePicker != null) filePicker.onReceiveValue(null);
        if (web != null) { web.removeJavascriptInterface("AndroidRecorder"); web.destroy(); web = null; }
        worker.shutdown(); aiWorker.shutdownNow(); super.onDestroy();
    }
}
