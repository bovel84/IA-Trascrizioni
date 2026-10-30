package it.voicescribe.recorder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
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

/** Original VoiceScribe UI; audio capture belongs exclusively to the native service. */
public final class MainActivity extends Activity {
    private static final String PAGE = "https://appassets.androidplatform.net/assets/index.html";
    private WebView web;
    private volatile TranscriptPlayer transcriptPlayer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService aiWorker = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(4));
    private volatile boolean starting, retrying, diarizing;
    private final ExecutorService aiCancellation = Executors.newSingleThreadExecutor();
    private final ConcurrentMap<String, AiHttp.Control> aiRequests = new ConcurrentHashMap<>();
    private volatile String retrySessionId = "";
    private String key, model, language;
    private boolean pendingStart;
    private static final int PLAYBACK_PERMISSION = 40;
    private String captureSource = RecordingService.MICROPHONE;
    private boolean awaitingProjection;
    private Intent projectionData;
    private int projectionResult;
    private ValueCallback<Uri[]> filePicker;
    private DocumentExports documentExports;
    private DocumentExports.Listener exportListener;
    private Intent pendingImport;
    private String importKey,importModel,importLanguage;

    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        documentExports=DocumentExports.get(this);
        exportListener=new DocumentExports.Listener(){
            public boolean pick(DocumentExports.Entry entry){
                if(!hasWindowFocus()||isFinishing()||isDestroyed())return false;
                try {
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(entry.type).putExtra(Intent.EXTRA_TITLE,entry.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),30);
                    return true;
                } catch(Exception e){toast("Selettore file non disponibile: riprova Scarica Audio");return false;}
            }
            public void changed(String message){
                if(message.startsWith("File salvato")||message.contains("non riuscit"))toast(message);
            }
        };
        documentExports.attach(exportListener);
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
                    if (AppAssets.allowed(path)) {
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
        awaitingProjection = false; projectionData = null; key = null;
        if (web != null) web.evaluateJavascript("window.nativeRecorderError && window.nativeRecorderError(" + JSONObject.quote(message) + ")", null);
    }
    private void requestStart(String apiKey, String selectedModel, String lang, String source) {
        if (RecordingService.active || AudioImportService.active || starting || retrying || diarizing) return;
        if(transcriptPlayer!=null)transcriptPlayer.close();
        if (!hasWindowFocus() || isFinishing()) { startError("Torna nell'app per avviare la registrazione"); return; }
        if (RecordingService.PLAYBACK.equals(source) && Build.VERSION.SDK_INT < 29) { startError("L'audio del dispositivo richiede Android 10 o successivo"); return; }
        if (apiKey.trim().isEmpty() || selectedModel.trim().isEmpty() || !lang.matches("[a-z]{2}")) { startError("Inserisci chiave Whisper, modello e lingua"); return; }
        captureSource = source; projectionData = null;
        key = apiKey; model = selectedModel; language = lang; starting = true;
        retrySessionId = "";
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
        if(focused&&documentExports!=null)documentExports.resume();
        if (focused && pendingStart) { pendingStart = false; begin(); }
        if(focused&&pendingImport!=null){Intent next=pendingImport;pendingImport=null;launchImport(next);}
    }
    private void launchImport(Intent intent){try{retrySessionId="";startForegroundService(intent);}catch(Exception e){toast("Importazione non avviata: "+e.getMessage());}}
    private void begin() {
        if (!hasWindowFocus() || isFinishing()) { startError("Torna nell'app e premi Registra"); return; }
        try {
            if (RecordingService.PLAYBACK.equals(captureSource) && projectionData == null) {
                if (awaitingProjection) return;
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                Intent consent = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
                awaitingProjection = true;
                startActivityForResult(consent, PLAYBACK_PERMISSION);
                return;
            }
            Intent service = new Intent(this, RecordingService.class).putExtra("key", key).putExtra("model", model).putExtra("language", language)
                .putExtra("source", captureSource);
            if (projectionData != null) service.putExtra("projectionResult", projectionResult).putExtra("projectionData", projectionData);
            startForegroundService(service);
            projectionData = null; // A consent token is consumed once, never reused for another recording.
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
        int pending = 0, transcribed = 0, empty = 0; File[] files = Transcriber.segments(dir);
        boolean local=VoiceData.enabled(dir); JSONObject voiceNames=VoiceData.names(dir);
        for (File wav : files) {
            size += Math.max(0, wav.length() - 44);
            if (Transcriber.result(wav).exists()) {
                transcribed++;
                String text = new String(Files.readAllBytes(Transcriber.result(wav).toPath()), StandardCharsets.UTF_8);
                if (text.trim().isEmpty()) { empty++; continue; }
                JSONObject quality=VoiceData.readQuality(wav);
                if(local) {
                    try {
                        JSONArray groups=VoiceData.completeGroups(wav,text),recognized=new JSONArray();
                        long base=Long.parseLong(wav.getName().replace(".wav",""));
                        for(int i=0;i<groups.length();i++){
                            JSONObject group=groups.getJSONObject(i);int speaker=group.optInt("speaker",0);
                            JSONObject turn=new JSONObject().put("nativeSegmentId",id+"/"+wav.getName()+"#"+i)
                                .put("offsetMs",base+group.getLong("start")).put("endMs",base+group.getLong("end"))
                                .put("text",group.getString("text")).put("speaker",speaker)
                                .put("speakerName",speaker==0?"Non assegnato":voiceNames.optString(String.valueOf(speaker),"Voce "+speaker))
                                .put("speakerSource","local").put("speakerReview",speaker==0||group.optBoolean("review",true))
                                .put("speakerSimilarity",group.optDouble("similarity",-1));
                            if(speaker>0)turn.put("nativeVoiceId",id+"/voice-"+speaker);
                            else turn.put("speakerUnassignedReason",group.optString("unassignedReason",""));
                            recognized.put(VoiceData.annotateQuality(turn,quality));
                        }
                        if(groups.length()>0){for(int i=0;i<recognized.length();i++)entries.put(recognized.getJSONObject(i));continue;}
                    }catch(Exception ignored){ /* Raw transcript remains available below. */ }
                }
                JSONObject entry = new JSONObject().put("nativeSegmentId", id + "/" + wav.getName()).put("offsetMs", Long.parseLong(wav.getName().replace(".wav", ""))).put("text", text);
                entry.put("endMs", entry.getLong("offsetMs") + Math.max(0, wav.length() - 44) * 1000 / WavFile.BYTES_PER_SECOND);
                File label = new File(wav.getPath() + ".speaker.json");
                if(local)entry.put("speaker",0).put("speakerName","Non assegnato").put("speakerSource","local").put("speakerReview",true).put("speakerUnassignedReason","unavailable");
                if (!local && label.exists()) {
                    try {
                        JSONObject speaker = new JSONObject(new String(Files.readAllBytes(label.toPath()), StandardCharsets.UTF_8));
                        entry.put("speaker", speaker.optInt("speaker", 0)).put("speakerName", speaker.optString("speakerName", "Non assegnato"));
                    } catch (Exception ignored) { /* Older/corrupt labels stay unassigned. */ }
                }
                entries.put(VoiceData.annotateQuality(entry,quality));
            } else pending++;
        }
        File[] partials = dir.listFiles((d,n) -> n.endsWith(".part"));
        int recoverable = 0;
        if (partials != null) for (File partial : partials) {
            size += Math.max(0, partial.length() - 44);
            if (partial.length() > 44) recoverable++;
        }
        boolean imported=new File(dir,"import.json").isFile(),incomplete=false;
        if(imported)try{incomplete=!VoiceData.read(new File(dir,"import.json")).optBoolean("decoded");}catch(Exception e){incomplete=true;}
        return new JSONObject().put("sessionId", id).put("entries", entries).put("elapsedMs", size * 1000 / WavFile.BYTES_PER_SECOND).put("chunkCount", files.length).put("pending", pending+recoverable+(incomplete?1:0)).put("sessionLocalVoices",local).put("importedAudio",imported).put("importIncomplete",incomplete)
            .put("partialCount",recoverable).put("transcribedCount",transcribed).put("emptyCount",empty).put("lastError",TranscriptionBatch.lastError(dir));
    }
    private void repair(File dir) throws IOException {
        if (RecordingService.active && dir.getName().equals(latest())) return;
        if(AudioImportService.active&&dir.getName().equals(AudioImportService.sessionId))return;
        File[] files = dir.listFiles((d,n) -> n.endsWith(".part"));
        if (files != null) for (File file : files) {
            WavFile.repair(file);
            if (!file.renameTo(new File(file.getPath().replace(".part", ".wav")))) throw new IOException("Recupero audio fallito");
        }
    }
    private File combinedAudio(String id) throws IOException {
        File dir=session(id);
        if(RecordingService.active&&dir.getName().equals(latest()))throw new IOException("Ferma prima la registrazione per esportare tutto l’audio");
        repair(dir);
        return AudioExport.combine(dir,documentExports.directory());
    }
    public final class RecorderBridge {
        @JavascriptInterface public void setTheme(String theme){ui.post(()->{
            boolean light="light".equals(theme);int color=light?0xfff4f7f6:0xff101519;
            getWindow().setStatusBarColor(color);getWindow().setNavigationBarColor(color);web.setBackgroundColor(color);
            int flags=getWindow().getDecorView().getSystemUiVisibility(),mask=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(light?flags|mask:flags&~mask);
        });}
        @JavascriptInterface public void importAudio(String apiKey,String selectedModel,String lang){ui.post(()->{
            if(RecordingService.active||AudioImportService.active||starting||retrying||diarizing)return;
            if(apiKey==null||apiKey.trim().isEmpty()||selectedModel==null||selectedModel.trim().isEmpty()||lang==null||!lang.matches("[a-z]{2}")){toast("Inserisci chiave Whisper, modello e lingua");return;}
            importKey=apiKey;importModel=selectedModel;importLanguage=lang;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),50);
        });}
        @JavascriptInterface public void cancelImport(){ui.post(()->{if(AudioImportService.active)startService(new Intent(MainActivity.this,AudioImportService.class).setAction(AudioImportService.CANCEL));});}
        @JavascriptInterface public void selectSpeaker(int id, String name) {
            if (id < 1 || id > 32 || name == null || name.trim().isEmpty()) return;
            RecordingService.selectedSpeaker = new RecordingService.SpeakerSelection(id, name.trim().substring(0, Math.min(80, name.trim().length())));
        }
        @JavascriptInterface public void diarize(String requestId, String sessionId, String apiKey, String lang) {
            if (!requestId.matches("[0-9]{1,12}")) return;
            ui.post(() -> {
                if (RecordingService.active || AudioImportService.active || starting || retrying || diarizing) { aiReply(requestId, 0, "", "Attendi il completamento della registrazione"); return; }
                diarizing = true;
                worker.execute(() -> {
                    File audio = null;
                    try {
                        File dir = session(sessionId); repair(dir);
                        long size = 44;
                        for (File part : Transcriber.segments(dir)) size += Math.max(0, part.length() - 44);
                        if (size <= 44 || size > 2_000_000_000L) throw new IOException("Audio vuoto o superiore a 2 GB");
                        audio = combinedAudio(sessionId);
                        aiReply(requestId, 200, MeetingDiarizer.analyse(audio, apiKey, lang), "");
                    } catch (Exception e) { aiReply(requestId, 0, "", "Analisi voci non riuscita: " + e.getMessage()); }
                    finally { if (audio != null) audio.delete(); diarizing = false; }
                });
            });
        }
        @JavascriptInterface public void aiRequest(String id, String provider, String path, String apiKey, String body) {
            if (!id.matches("[0-9]{1,12}")) return;
            AiHttp.Control control = new AiHttp.Control();
            if (aiRequests.putIfAbsent(id, control) != null) return;
            Runnable request = () -> {
                try {
                    AiHttp.Result result = AiHttp.request(provider, path, apiKey, body, control);
                    if (!control.isCancelled()) aiReply(id, result.status, result.body, "");
                } catch (Exception e) { if (!control.isCancelled()) aiReply(id, 0, "", "Richiesta AI non riuscita: controlla connessione, chiave e modello."); }
                finally { aiRequests.remove(id, control); }
            };
            try { aiWorker.execute(request); }
            catch (RejectedExecutionException e) { aiRequests.remove(id, control); aiReply(id, 0, "", "Troppe richieste AI: attendi e riprova."); }
        }
        @JavascriptInterface public void cancelAiRequest(String id) {
            AiHttp.Control control = aiRequests.remove(id);
            if (control != null) aiCancellation.execute(control::cancel);
        }
        @JavascriptInterface public void copyText(String text) {
            if (text == null || text.length() > 2_000_000) return;
            ui.post(() -> getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("VoiceScribe", text)));
        }
        @JavascriptInterface public boolean localVoicesAvailable() {
            try(InputStream in=getAssets().open("voices/segmentation.onnx")){return in.read()!=-1;}catch(Exception e){return false;}
        }
        @JavascriptInterface public void setLocalVoices(boolean enabled) {
            ui.post(()->{if(!RecordingService.active&&!AudioImportService.active&&!starting&&!retrying&&!diarizing)getSharedPreferences("native",0).edit().putBoolean("localVoices",enabled).apply();});
        }
        @JavascriptInterface public void renameVoice(String token,String value) {
            if(token==null||!token.matches("session-[0-9]{13}/voice-[0-9]{1,2}")||value==null||value.trim().isEmpty())return;
            worker.execute(()->{try{String[] parts=token.split("/");int voice=Integer.parseInt(parts[1].substring(6));
                if(voice<1||voice>32)return;String name=value.trim();VoiceData.rename(session(parts[0]),voice,name.substring(0,Math.min(80,name.length())));
            }catch(Exception e){ui.post(()->toast("Nome vocale non salvato: riprova"));}});
        }
        @JavascriptInterface public boolean canCapturePlayback() { return Build.VERSION.SDK_INT >= 29; }
        @JavascriptInterface public void setTranscriptionGlossary(String value){getSharedPreferences("native",0).edit().putString("glossary",SpeechContext.glossary(value)).apply();}
        @JavascriptInterface public void listen(String segment,long start,long end){ui.post(()->{
            if(!hasWindowFocus()||isFinishing()||isDestroyed())return;
            if(RecordingService.active||AudioImportService.active||starting){toast("Ferma prima la registrazione per riascoltare");return;}
            try{if(transcriptPlayer==null)transcriptPlayer=new TranscriptPlayer(getFilesDir(),MainActivity.this::toast);transcriptPlayer.play(segment,start,end);}catch(Exception e){toast(e.getMessage());}
        });}
        @JavascriptInterface public void stopListening(){ui.post(()->{if(transcriptPlayer!=null)transcriptPlayer.close();});}
        @JavascriptInterface public void start(String apiKey, String selectedModel, String lang) { ui.post(() -> requestStart(apiKey, selectedModel, lang, RecordingService.MICROPHONE)); }
        @JavascriptInterface public void startPlayback(String apiKey, String selectedModel, String lang) { ui.post(() -> requestStart(apiKey, selectedModel, lang, RecordingService.PLAYBACK)); }
        @JavascriptInterface public void stop() { ui.post(() -> { if (RecordingService.active) startService(new Intent(MainActivity.this, RecordingService.class).setAction(RecordingService.STOP)); }); }
        @JavascriptInterface public void pause(boolean value) { if (RecordingService.recording) RecordingService.paused = value; }
        @JavascriptInterface public String state() {
            try {
                String imported=AudioImportService.sessionId;
                String id = AudioImportService.active&&!imported.isEmpty()&&new File(getFilesDir(),imported).isDirectory()?imported:retrySessionId.isEmpty()?latest():retrySessionId; JSONObject state = id.isEmpty() ? new JSONObject() : snapshot(id);
                boolean importSession=id.equals(AudioImportService.sessionId);
                state.put("importActive",AudioImportService.active).put("importReady",importSession&&AudioImportService.ready).put("importSessionId",AudioImportService.sessionId)
                    .put("importPhase",AudioImportService.phase).put("importStatus",AudioImportService.status).put("importPercent",AudioImportService.percent);
                return state.put("active", RecordingService.active).put("recording", RecordingService.recording).put("paused", RecordingService.paused)
                    .put("starting", starting).put("retrying", retrying).put("diarizing", diarizing).put("level", RecordingService.level).put("listening",transcriptPlayer!=null&&transcriptPlayer.isPlaying())
                    .put("exportBusy",documentExports.busy()).put("exportFailed",documentExports.failed()).put("exportStatus",documentExports.status())
                    .put("source", RecordingService.source).put("localVoices",RecordingService.active?RecordingService.localVoices:getSharedPreferences("native",0).getBoolean("localVoices",true)).put("voiceStatus",RecordingService.voiceStatus).put("status", RecordingService.status).toString();
            } catch (Exception e) { return "{\"active\":false,\"recording\":false,\"status\":\"Lettura sessione fallita\"}"; }
        }
        @JavascriptInterface public String sessions() {
            JSONArray data = new JSONArray(); File[] dirs = getFilesDir().listFiles(f -> f.isDirectory() && f.getName().matches("session-[0-9]{13}"));
            if (dirs != null) { Arrays.sort(dirs, Comparator.comparing(File::getName).reversed()); for (File dir : dirs) try { data.put(snapshot(dir.getName())); } catch (Exception ignored) { } }
            return data.toString();
        }
        @JavascriptInterface public void retry(String id, String apiKey, String selectedModel, String lang) {
            ui.post(() -> {
                if (RecordingService.active || AudioImportService.active || retrying || diarizing || !hasWindowFocus()) return;
                if (apiKey == null || apiKey.trim().isEmpty() || selectedModel == null || selectedModel.trim().isEmpty() || lang == null || !lang.matches("[a-z]{2}")) { toast("Inserisci chiave Whisper, modello e lingua"); return; }
                try{if(new File(session(id),"import.json").isFile()){launchImport(new Intent(MainActivity.this,AudioImportService.class).putExtra("resume",id).putExtra("key",apiKey).putExtra("model",selectedModel).putExtra("language",lang));return;}}catch(Exception e){toast(e.getMessage());return;}
                retrying = true; retrySessionId = id;
                worker.execute(() -> {
                    try {
                        File dir = session(id); repair(dir);
                        final NativeVoiceEngine[] engine = { null };
                        TranscriptionBatch batch = new TranscriptionBatch(dir, wav -> {
                            if(VoiceData.enabled(dir)&&!VoiceData.timeline(wav).exists()){
                                try{if(engine[0]==null)engine[0]=new NativeVoiceEngine(MainActivity.this,dir);engine[0].analyse(wav);}
                                catch(Exception|LinkageError e){try{NativeVoiceEngine.unavailable(wav,"Analisi voci non riuscita: "+e.getMessage());}catch(Exception ignored){ /* Preserve full audio even when attribution fails. */ }}
                            }
                            Transcriber.transcribe(wav,apiKey,selectedModel,lang);
                        });
                        try {
                            for(File wav:Transcriber.segments(dir)){
                                batch.process(wav);
                                RecordingService.status = batch.finish();
                            }
                        }finally{if(engine[0]!=null)engine[0].close();}
                        RecordingService.status = batch.finish();
                    } catch (Exception e) { RecordingService.status = "Audio conservato · " + e.getMessage(); }
                    finally { retrying = false; }
                });
            });
        }
        @JavascriptInterface public void batterySettings() { ui.post(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))); }
        @JavascriptInterface public void retryExport(){ui.post(()->documentExports.retry());}
        @JavascriptInterface public void cancelExport(){ui.post(()->documentExports.cancel());}
        @JavascriptInterface public void exportAudio(String id) {
            documentExports.prepare(()->{
                File dir=session(id),source=new File(dir,"source.audio"),metadata=new File(dir,"import.json");
                if(source.isFile()&&metadata.isFile()){
                    JSONObject info=VoiceData.read(metadata);
                    if(!info.optBoolean("sourceComplete"))throw new IOException("Copia incompleta: conserva il file originale selezionato");
                    String name=info.optString("sourceName","audio_originale").replaceAll("[^a-zA-Z0-9._-]","_"),type=info.optString("sourceMime","application/octet-stream");
                    return new DocumentExports.Entry(source,name,type,false);
                }
                File file=combinedAudio(id);
                return new DocumentExports.Entry(file,"voicescribe_"+id+".wav","audio/wav",true);
            });
        }
        @JavascriptInterface public void download(String name, String type, String base64) {
            if(base64.length()>64*1024*1024){ui.post(()->toast("Export troppo grande"));return;}
            documentExports.prepare(()->{
                byte[] data=android.util.Base64.decode(base64,android.util.Base64.DEFAULT);
                if(data.length==0)throw new IOException("Il contenuto da esportare è vuoto");
                File file=File.createTempFile("document-",".tmp",documentExports.directory());
                Files.write(file.toPath(),data);
                return new DocumentExports.Entry(file,name.replaceAll("[^a-zA-Z0-9._-]","_"),type.isEmpty()?"application/octet-stream":type,true);
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
        if(request==50){
            if(result==RESULT_OK&&data!=null&&data.getData()!=null){pendingImport=new Intent(this,AudioImportService.class).setData(data.getData()).putExtra("key",importKey).putExtra("model",importModel).putExtra("language",importLanguage);
                if(hasWindowFocus()){Intent next=pendingImport;pendingImport=null;launchImport(next);}}
            else if(web!=null)web.evaluateJavascript("window.nativeImportRequested=false",null);
            importKey=null;importModel=null;importLanguage=null;return;
        }
        if (request == PLAYBACK_PERMISSION) {
            if (!starting || !awaitingProjection) return;
            awaitingProjection = false;
            if (result != RESULT_OK || data == null) { startError("Cattura audio del dispositivo annullata"); return; }
            projectionData = data; projectionResult = result;
            pendingStart = true;
            if (hasWindowFocus()) { pendingStart = false; begin(); }
            return;
        }
        if (request == 10 && filePicker != null) { filePicker.onReceiveValue(result == RESULT_OK && data != null && data.getData() != null ? new Uri[] { data.getData() } : null); filePicker = null; }
        if(request==30)documentExports.result(result,data==null?null:data.getData(),data==null?0:data.getFlags());
    }

    public void onBackPressed() { web.evaluateJavascript("document.querySelectorAll('.modal.active').forEach(m => m.classList.remove('active'))", null); moveTaskToBack(true); }
    protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    protected void onPause() {
        if(transcriptPlayer!=null)transcriptPlayer.close();
        if (web != null) { web.evaluateJavascript("typeof autosaveNow === 'function' && autosaveNow()", null); web.onPause(); }
        super.onPause();
    }
    protected void onDestroy() {
        if(documentExports!=null)documentExports.detach(exportListener);
        if(transcriptPlayer!=null)transcriptPlayer.close();
        if (filePicker != null) filePicker.onReceiveValue(null);
        if (web != null) { web.removeJavascriptInterface("AndroidRecorder"); web.destroy(); web = null; }
        for (AiHttp.Control control : aiRequests.values()) aiCancellation.execute(control::cancel);
        aiRequests.clear(); aiCancellation.shutdown(); worker.shutdown(); aiWorker.shutdownNow(); super.onDestroy();
    }
}
