package it.voicescribe.recorder;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

public final class RecordingService extends Service {
    static final String STOP = "it.voicescribe.STOP";
    static volatile boolean active, recording, paused;
    static volatile String status = "Pronto";
    private volatile boolean stopping;
    private volatile String captureIssue;
    private volatile AudioRecord audio;
    private Thread capture;
    private PowerManager.WakeLock cpu;
    private final ExecutorService uploads = Executors.newSingleThreadExecutor();
    private String key, model, language;
    private File session;

    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("recording", "Registrazione audio", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Microfono e trascrizione in corso");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecordingService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("VoiceScribe Audio")
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
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification("Avvio microfono…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(1, notification("Avvio microfono…"));
            key = intent.getStringExtra("key"); model = intent.getStringExtra("model"); language = intent.getStringExtra("language");
            if (key == null || key.trim().isEmpty()) throw new IOException("Chiave Groq mancante");
            session = new File(getFilesDir(), "session-" + System.currentTimeMillis());
            if (!session.mkdir()) throw new IOException("Impossibile creare la sessione");
            active = recording = true; paused = false; stopping = false;
            // AudioRecord delivers PCM to application code: that thread must keep writing
            // and closing segments while the CPU would otherwise sleep. No screen lock.
            cpu = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceScribe:Capture");
            cpu.acquire();
            capture = new Thread(this::captureAudio, "voicescribe-capture"); capture.start();
        } catch (Exception e) {
            status = "Avvio fallito: " + e.getMessage(); active = recording = false;
            releaseCpu(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
        // Never restart the microphone without a fresh action in the visible activity.
        return START_NOT_STICKY;
    }
    private void captureAudio() {
        WavFile wav = null; File partial = null;
        long totalBytes = 0, segmentBytes = 0;
        String failure = null;
        try {
            int min = AudioRecord.getMinBufferSize(WavFile.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("Formato microfono non supportato");
            audio = new AudioRecord(MediaRecorder.AudioSource.MIC, WavFile.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 32768));
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microfono non disponibile");
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microfono non avviato");
            if (Build.VERSION.SDK_INT >= 29) audio.registerAudioRecordingCallback(getMainExecutor(), new AudioManager.AudioRecordingCallback() {
                public void onRecordingConfigChanged(java.util.List<AudioRecordingConfiguration> configs) {
                    for (AudioRecordingConfiguration config : configs) {
                        if (audio != null && config.getClientAudioSessionId() == audio.getAudioSessionId() && config.isClientSilenced()) {
                            captureIssue = status = "Microfono silenziato dal sistema: sessione interrotta";
                            recording = false;
                        }
                    }
                }
            });
            announce("Registrazione in corso · puoi bloccare lo schermo");
            byte[] buf = new byte[4096];
            while (recording) {
                int n = audio.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IOException("Registrazione interrotta (AudioRecord " + n + ")");
                if (n == 0) continue;
                if (paused) {
                    if (wav != null) { wav.close(); wav = null; submit(finalizeSegment(partial)); partial = null; segmentBytes = 0; }
                    continue;
                }
                if (wav == null) {
                    String offset = String.format(java.util.Locale.ROOT, "%012d", totalBytes * 1000 / WavFile.BYTES_PER_SECOND);
                    partial = new File(session, offset + ".part"); wav = new WavFile(partial);
                }
                wav.write(buf, n); totalBytes += n; segmentBytes += n;
                if (segmentBytes >= WavFile.BYTES_PER_SECOND * 120L) {
                    wav.close(); wav = null;
                    submit(finalizeSegment(partial)); partial = null; segmentBytes = 0;
                }
            }
        } catch (Exception e) { failure = "Registrazione interrotta: " + e.getMessage(); }
        finally {
            recording = false; paused = false;
            if (audio != null) {
                try { audio.stop(); } catch (Exception ignored) { }
                audio.release(); audio = null;
            }
            try { if (wav != null) { wav.close(); submit(finalizeSegment(partial)); } }
            catch (Exception e) { failure = "Audio parziale da recuperare: " + e.getMessage(); }
            if (failure == null) failure = captureIssue;
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
                releaseCpu(); active = false;
                new Handler(getMainLooper()).post(() -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
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
            try { Transcriber.transcribe(file, key, model, language); }
            catch (Exception e) { status = "Audio salvato · " + e.getMessage() + " · usa Riprova"; }
        });
    }
    private synchronized void releaseCpu() {
        if (cpu != null && cpu.isHeld()) cpu.release(); cpu = null;
    }
    public void onDestroy() {
        recording = false; stopping = true;
        if (audio != null) { try { audio.stop(); } catch (Exception ignored) { } }
        releaseCpu();
        super.onDestroy();
    }
    public IBinder onBind(Intent intent) { return null; }
}
