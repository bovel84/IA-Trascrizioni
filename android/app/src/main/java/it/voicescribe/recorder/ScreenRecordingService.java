package it.voicescribe.recorder;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.io.IOException;

/** Owns the consent token and the MP4 recorder while the user changes apps. */
public final class ScreenRecordingService extends Service {
    static final String SOURCE = "screen";
    static final String STOP = "it.voicescribe.STOP_SCREEN";
    static volatile boolean active, recording;
    static volatile String status = "";
    private static volatile long startedAt;
    private static final int NOTIFICATION = 3;
    private static final String CHANNEL = "screen-recording";

    private HandlerThread thread;
    private Handler worker;
    private MediaProjection projection;
    private VirtualDisplay display;
    private Surface surface;
    private MediaRecorder recorder;
    private File partial;
    private boolean started, finished;
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { finish("La condivisione dello schermo è terminata"); }
    };

    static long elapsedMs() {
        return recording ? Math.max(0, SystemClock.elapsedRealtime() - startedAt) : 0;
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Registrazione dello schermo", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification(String message) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, NOTIFICATION,
            new Intent(this, ScreenRecordingService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("VoiceScribe · Schermo").setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "Ferma", stop).build()).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            if (worker != null) worker.post(() -> finish(null));
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        status = "Avvio video dello schermo…";
        try {
            if (RecordingService.active || AudioImportService.active) throw new IOException("Attendi l’audio in corso");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION, notification(status), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else startForeground(NOTIFICATION, notification(status));
            active = true;
            thread = new HandlerThread("voicescribe-screen");
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(() -> startCapture(intent));
        } catch (Exception e) {
            status = "Avvio video non riuscito · " + e.getMessage();
            active = false;
            if (thread != null) thread.quitSafely();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void startCapture(Intent intent) {
        try {
            Intent consent = intent.getParcelableExtra("projectionData");
            if (consent == null || intent.getIntExtra("projectionResult", Activity.RESULT_CANCELED) != Activity.RESULT_OK) {
                throw new IOException("Condivisione dello schermo annullata");
            }
            int width = intent.getIntExtra("width", 0);
            int height = intent.getIntExtra("height", 0);
            int density = intent.getIntExtra("density", 0);
            if (width < 2 || height < 2 || density <= 0) throw new IOException("Dimensioni dello schermo non disponibili");
            partial = ScreenVideos.createPartial(getFilesDir());
            long budget = partial.getUsableSpace() - 64L * 1024 * 1024;
            if (budget < 16L * 1024 * 1024) throw new IOException("Spazio insufficiente per registrare");

            int[] size = prepareRecorder(width, height, budget);
            recorder.setOnErrorListener((rec, what, extra) -> worker.post(() -> finish("Registrazione video interrotta")));
            recorder.setOnInfoListener((rec, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    worker.post(() -> finish("Spazio disponibile per il video esaurito"));
                }
            });
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
            if (projection == null) throw new IOException("Condivisione dello schermo non disponibile");
            projection.registerCallback(projectionCallback, worker);
            surface = recorder.getSurface();
            display = projection.createVirtualDisplay("VoiceScribe Schermo", size[0], size[1], density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, worker);
            if (display == null) throw new IOException("Impossibile acquisire lo schermo");
            recorder.start();
            started = true;
            startedAt = SystemClock.elapsedRealtime();
            status = "Schermo in registrazione";
            recording = true;
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(status));
        } catch (Exception e) {
            finish("Avvio video non riuscito · " + e.getMessage());
        }
    }

    private int[] prepareRecorder(int width, int height, long budget) throws IOException {
        Exception failure = null;
        int lastSize = 0;
        for (int limit : new int[] {1920, 1280, 720}) {
            int longest = Math.min(limit, Math.max(width, height));
            if (longest == lastSize) continue;
            lastSize = longest;
            double scale = longest / (double) Math.max(width, height);
            int w = Math.max(2, ((int) (width * scale)) & ~1);
            int h = Math.max(2, ((int) (height * scale)) & ~1);
            MediaRecorder candidate = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            try {
                candidate.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                candidate.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                candidate.setVideoSize(w, h);
                candidate.setVideoFrameRate(30);
                candidate.setVideoEncodingBitRate(Math.max(900_000, Math.min(6_000_000, w * h * 3)));
                candidate.setMaxFileSize(budget);
                candidate.setOutputFile(partial.getAbsolutePath());
                candidate.prepare();
                recorder = candidate;
                return new int[] {w, h};
            } catch (IOException | RuntimeException e) {
                failure = e;
                candidate.release();
            }
        }
        throw new IOException("Codifica video non disponibile", failure);
    }

    private void finish(String reason) {
        if (finished) return;
        finished = true;
        recording = false;
        status = "Salvataggio video in corso…";
        try {
            if (started) recorder.stop();
        } catch (RuntimeException ignored) { /* An early stop or a file-size limit may already have stopped the encoder. */ }
        if (display != null) display.release();
        if (surface != null) surface.release();
        if (recorder != null) recorder.release();
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
        }

        if (ScreenVideos.hasFrames(partial)) {
            try {
                ScreenVideos.publish(partial);
                status = reason == null ? "Video salvato in Progetti" : reason + " · video salvato in Progetti";
            } catch (IOException e) {
                status = "Video acquisito · riapri Progetti per riprovare il salvataggio";
            }
        } else {
            if (partial != null) partial.delete();
            status = reason != null ? reason + " · nessun video salvato" : "Video troppo breve per essere salvato";
        }
        new Handler(getMainLooper()).post(() -> {
            active = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
        thread.quitSafely();
    }

    @Override public void onDestroy() {
        if (worker != null) worker.post(() -> finish("Registrazione video terminata"));
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
