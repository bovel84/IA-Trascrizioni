package it.voicescribe.recorder;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

public final class RecordingService extends Service {
    static final String STOP = "it.voicescribe.STOP";
    static final String MICROPHONE = "microphone", PLAYBACK = "playback";
    static volatile String source = MICROPHONE;
    static volatile boolean active, recording, paused;
    static volatile String status = "Pronto";
    static volatile double level;
    static volatile String voiceStatus="Riconoscimento voci pronto";
    static volatile boolean localVoices;
    private NativeVoiceEngine voiceEngine;
    private static final long SEGMENT_SECONDS = 30L; // First transcript appears sooner than after a 2-minute segment.
    private volatile boolean stopping;
    private volatile String captureIssue;
    private volatile AudioRecord audio;
    private MediaProjection projection;
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            captureIssue = "Condivisione audio terminata da Android · audio acquisito conservato";
            recording = false;
            AudioRecord recorder = audio;
            if (recorder != null) try { recorder.stop(); } catch (Exception ignored) { }
        }
    };
    private Thread capture;
    private PowerManager.WakeLock cpu;
    private final ExecutorService uploads = Executors.newSingleThreadExecutor();
    private String key, model, language;
    private File session;
    static final class SpeakerSelection {
        final int id; final String name;
        SpeakerSelection(int id, String name) { this.id = id; this.name = name; }
    }
    static volatile SpeakerSelection selectedSpeaker = new SpeakerSelection(0, "Non assegnato");

    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("recording", "Registrazione audio", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Registrazione microfono o audio del dispositivo e trascrizione");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecordingService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("VoiceScribe Pro")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Ferma", stop).build()).build();
    }
    private void announce(String text) {
        status = text;
        getSystemService(NotificationManager.class).notify(1, notification(text));
    }
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) {
            stopping = true;
            recording = false;
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        try {
            source = PLAYBACK.equals(intent.getStringExtra("source")) ? PLAYBACK : MICROPHONE;
            captureIssue = null; level = 0;
            boolean playback = PLAYBACK.equals(source);
            if (playback && Build.VERSION.SDK_INT < 29) throw new IOException("Audio del dispositivo disponibile da Android 10");
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(playback ? "Avvio audio del dispositivo…" : "Avvio microfono…"),
                playback ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(1, notification("Avvio microfono…"));
            key = intent.getStringExtra("key"); model = intent.getStringExtra("model"); language = intent.getStringExtra("language");
            if (key == null || key.trim().isEmpty()) throw new IOException("Chiave Groq mancante");
            if (playback) {
                Intent consent = intent.getParcelableExtra("projectionData");
                int result = intent.getIntExtra("projectionResult", Activity.RESULT_CANCELED);
                if (consent == null || result != Activity.RESULT_OK) throw new IOException("Autorizza la cattura audio dall'app");
                // Android 14+ requires the mediaProjection FGS before obtaining the token.
                projection = getSystemService(MediaProjectionManager.class).getMediaProjection(result, consent);
                if (projection == null) throw new IOException("Cattura audio non autorizzata");
                projection.registerCallback(projectionCallback, new Handler(getMainLooper()));
            }
            session = new File(getFilesDir(), "session-" + System.currentTimeMillis());
            if (!session.mkdir()) throw new IOException("Impossibile creare la sessione");
            localVoices=getSharedPreferences("native",0).getBoolean("localVoices",true);
            VoiceData.write(new File(session,"native-voices.json"),new org.json.JSONObject().put("enabled",localVoices).put("model",VoiceData.MODEL));
            voiceStatus=localVoices ? "Riconoscimento sul dispositivo · attendo il primo segmento" : "Attribuzione manuale dei partecipanti";
            getSharedPreferences("native", 0).edit().putString("latest", session.getName()).apply();
            active = recording = true; paused = false; stopping = false;
            // AudioRecord delivers PCM to application code: that thread must keep writing
            // and closing segments while the CPU would otherwise sleep. No screen lock.
            cpu = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceScribe:Capture");
            cpu.acquire();
            capture = new Thread(this::captureAudio, "voicescribe-capture"); capture.start();
        } catch (Exception e) {
            status = "Avvio fallito: " + e.getMessage(); active = recording = false;
            releaseProjection(); releaseCpu(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
        // Never restart capture without a fresh action (and fresh projection consent).
        return START_NOT_STICKY;
    }
    private void captureAudio() {
        WavFile wav = null; File partial = null;
        long totalBytes = 0, segmentBytes = 0;
        long silentBytes = 0;
        boolean silenceWarning = false;
        SpeakerSelection capturedSpeaker = selectedSpeaker;
        String failure = null;
        try {
            int min = AudioRecord.getMinBufferSize(WavFile.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("Formato audio non supportato");
            if (PLAYBACK.equals(source) && Build.VERSION.SDK_INT >= 29) {
                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(android.os.Process.myUid()).build();
                audio = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(WavFile.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min * 4, 32768)).build();
            } else {
                audio = new AudioRecord(MediaRecorder.AudioSource.MIC, WavFile.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 32768));
            }
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Sorgente audio non disponibile");
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Registrazione non avviata");
            if (Build.VERSION.SDK_INT >= 29) audio.registerAudioRecordingCallback(getMainExecutor(), new AudioManager.AudioRecordingCallback() {
                public void onRecordingConfigChanged(java.util.List<AudioRecordingConfiguration> configs) {
                    for (AudioRecordingConfiguration config : configs) {
                        if (audio != null && config.getClientAudioSessionId() == audio.getAudioSessionId() && config.isClientSilenced()) {
                            captureIssue = status = "Sorgente audio silenziata dal sistema: sessione interrotta";
                            recording = false;
                        }
                    }
                }
            });
            String captureStatus = PLAYBACK.equals(source) ? "Audio del dispositivo · apri l'app e avvia il video" : "Registrazione in corso · schermo bloccabile · segmenti da 30 s";
            announce(captureStatus);
            byte[] buf = new byte[4096];
            while (recording) {
                int n = audio.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IOException("Registrazione interrotta (AudioRecord " + n + ")");
                if (n == 0) continue;
                int peak = 0;
                for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((buf[i] & 255) | (buf[i + 1] << 8))));
                level = paused ? 0 : peak / 32768.0;
                if (paused) {
                    silentBytes = 0;
                    if (wav != null) { wav.close(); wav = null; submit(finalizeSegment(partial)); partial = null; segmentBytes = 0; }
                    continue;
                }
                if (PLAYBACK.equals(source)) {
                    silentBytes = peak == 0 ? silentBytes + n : 0;
                    boolean silent = silentBytes >= WavFile.BYTES_PER_SECOND * 8L;
                    if (silent && !silenceWarning) announce("Nessun audio ricevuto · avvia il video; l'app potrebbe vietare la cattura");
                    else if (!silent && silenceWarning) announce(captureStatus);
                    silenceWarning = silent;
                }
                SpeakerSelection selected = localVoices ? new SpeakerSelection(0,"Non assegnato") : selectedSpeaker;
                if (wav != null && selected.id != capturedSpeaker.id) {
                    wav.close(); wav = null; submit(finalizeSegment(partial)); partial = null; segmentBytes = 0;
                }
                if (wav == null) {
                    String offset = String.format(java.util.Locale.ROOT, "%012d", totalBytes * 1000 / WavFile.BYTES_PER_SECOND);
                    partial = new File(session, offset + ".part"); wav = new WavFile(partial);
                    capturedSpeaker = selected;
                    org.json.JSONObject label = new org.json.JSONObject().put("speaker", selected.id).put("speakerName", selected.name);
                    java.nio.file.Files.write(new File(session, offset + ".wav.speaker.json").toPath(), label.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                wav.write(buf, n); totalBytes += n; segmentBytes += n;
                if (segmentBytes >= WavFile.BYTES_PER_SECOND * SEGMENT_SECONDS) {
                    wav.close(); wav = null;
                    submit(finalizeSegment(partial)); partial = null; segmentBytes = 0;
                }
            }
        } catch (Exception e) { failure = "Registrazione interrotta: " + e.getMessage(); }
        finally {
            recording = false; paused = false;
            level = 0;
            if (audio != null) {
                try { audio.stop(); } catch (Exception ignored) { }
                audio.release(); audio = null;
            }
            try { if (wav != null) { wav.close(); submit(finalizeSegment(partial)); } }
            catch (Exception e) { failure = "Audio parziale da recuperare: " + e.getMessage(); }
            releaseProjection();
            if (captureIssue != null) failure = captureIssue;
            if (failure != null) status = failure;
            else if (stopping) status = "Audio salvato · completo le trascrizioni";
            final String captureFailure = failure;
            // Keep CPU alive for this bounded final queue too. Every request has timeouts.
            uploads.execute(() -> {
                int pending = 0;
                for (File file : Transcriber.segments(session)) if (!Transcriber.result(file).exists()) pending++;
                if (captureFailure != null) status = captureFailure;
                else if (pending == 0 && stopping) status = "Sessione salvata e trascritta";
                else if (pending > 0) status = "Audio salvato · " + pending + " segmenti da ritrascrivere";
                if(voiceEngine!=null){voiceEngine.close();voiceEngine=null;}
                releaseCpu();
                new Handler(getMainLooper()).post(() -> { active = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
            });
            uploads.shutdown();
        }
    }
    private File finalizeSegment(File part) throws IOException {
        File wav = new File(part.getPath().replace(".part", ".wav"));
        if (!part.renameTo(wav)) throw new IOException("Impossibile salvare " + part.getName());
        return wav;
    }
    private void submit(File file) {
        uploads.execute(() -> {
            try {
                if(localVoices && !VoiceData.timeline(file).exists()){
                    voiceStatus="Analisi voci sul dispositivo in corso…";
                    try{
                        if(voiceEngine==null)voiceEngine=new NativeVoiceEngine(this,session);
                        voiceEngine.analyse(file);
                        voiceStatus=voiceEngine.voices()>0?voiceEngine.voices()+" voci distinte sul dispositivo · rinomina con ✎":"Audio breve o ambiguo · voci da verificare";
                    }catch(Exception|LinkageError e){
                        voiceStatus="Voci locali da verificare: "+e.getMessage();NativeVoiceEngine.unavailable(file,voiceStatus);
                    }
                }
                Transcriber.transcribe(file, key, model, language);
            }
            catch (Exception e) { status = "Audio salvato · " + e.getMessage() + " · usa Riprova"; }
        });
    }
    private synchronized void releaseCpu() {
        if (cpu != null && cpu.isHeld()) cpu.release(); cpu = null;
    }
    private synchronized void releaseProjection() {
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop(); projection = null;
        }
    }
    public void onDestroy() {
        recording = false; stopping = true;
        if (audio != null) { try { audio.stop(); } catch (Exception ignored) { } }
        releaseProjection();
        releaseCpu();
        super.onDestroy();
    }
    public IBinder onBind(Intent intent) { return null; }
}
