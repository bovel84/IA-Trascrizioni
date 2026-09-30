package it.voicescribe.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.zip.*;

public final class MainActivity extends Activity {
    private EditText key, model, language;
    private TextView state, transcript;
    private Button start, stop, pause, retry, export;
    private Spinner sessions;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean retrying;
    private boolean pendingStart;
    private File exportSession;
    private final Runnable refresh = new Runnable() {
        public void run() { refreshText(); ui.postDelayed(this, 2000); }
    };
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24, 32, 24, 24);
        scroll.addView(layout); setContentView(scroll);
        label(layout, "VoiceScribe Audio", 24);
        label(layout, "Registrazione Android anche a schermo bloccato. Avvia qui, poi premi Home o blocca il telefono. L'audio viene inviato a Groq per la trascrizione.", 16);
        key = input(layout, "Chiave Groq (non salvata su disco)", ""); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false); key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        model = input(layout, "Modello Whisper", "whisper-large-v3-turbo");
        language = input(layout, "Lingua (es. it, en)", "it");
        start = button(layout, "Registra", this::requestStart);
        pause = button(layout, "Pausa / Riprendi", () -> {
            if (RecordingService.recording) { RecordingService.paused = !RecordingService.paused; refreshText(); }
        });
        stop = button(layout, "Ferma", () -> startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP)));
        state = label(layout, "Pronto", 16);
        label(layout, "Sessioni salvate", 18);
        sessions = new Spinner(this); layout.addView(sessions); reloadSessions();
        sessions.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) { refreshText(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        retry = button(layout, "Riprova segmenti non trascritti", this::retry);
        export = button(layout, "Esporta sessione (ZIP: audio + testo)", () -> {
            exportSession = selectedSession(); if (exportSession == null) return;
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);
            save.putExtra(Intent.EXTRA_TITLE, exportSession.getName() + ".zip"); startActivityForResult(save, 20);
        });
        button(layout, "Impostazioni batteria dell'app", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        label(layout, "Se il produttore interrompe il servizio, controlla Batteria nelle impostazioni dell'app. Non è richiesta alcuna esenzione automatica. Forza arresto e revoca del microfono interrompono la sessione.", 14);
        transcript = label(layout, "", 16); transcript.setTextIsSelectable(true);
        if (saved != null) { model.setText(saved.getString("model", "whisper-large-v3-turbo")); language.setText(saved.getString("language", "it")); }
    }
    private TextView label(LinearLayout layout, String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setPadding(0, 12, 0, 12); layout.addView(view); return view;
    }
    private EditText input(LinearLayout layout, String hint, String value) {
        EditText view = new EditText(this); view.setHint(hint); view.setText(value); view.setSingleLine(true); layout.addView(view); return view;
    }
    private Button button(LinearLayout layout, String text, Runnable action) {
        Button view = new Button(this); view.setText(text); view.setOnClickListener(v -> action.run()); layout.addView(view); return view;
    }
    private boolean credentials() {
        if (key.getText().toString().trim().isEmpty() || model.getText().toString().trim().isEmpty() || !language.getText().toString().trim().matches("[a-z]{2}")) {
            Toast.makeText(this, "Inserisci chiave, modello e lingua di due lettere", Toast.LENGTH_LONG).show(); return false;
        }
        return true;
    }
    private void requestStart() {
        if (RecordingService.active || retrying || !credentials()) return;
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) { requestPermissions(permissions.toArray(new String[0]), 10); return; }
        pendingStart = true;
        if (hasWindowFocus()) { pendingStart = false; begin(); }
    }
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && pendingStart) { pendingStart = false; begin(); }
    }
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 10) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { state.setText("Permesso microfono negato"); return; }
        // Notification permission is optional for FGS; Android still shows it in active apps.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Notifiche negate: servizio visibile in App attive; Ferma dall'app", Toast.LENGTH_LONG).show();
        pendingStart = true;
        if (hasWindowFocus()) { pendingStart = false; begin(); }
    }
    private void begin() {
        if (RecordingService.active || isFinishing() || !hasWindowFocus()) { state.setText("Torna nell'app e premi Registra"); return; }
        Intent intent = new Intent(this, RecordingService.class).putExtra("key", key.getText().toString().trim())
            .putExtra("model", model.getText().toString().trim()).putExtra("language", language.getText().toString().trim());
        try { startForegroundService(intent); start.setEnabled(false); ui.postDelayed(() -> { reloadSessions(); refreshText(); }, 500); }
        catch (Exception e) { state.setText("Avvio non consentito: " + e.getMessage()); }
    }
    private File selectedSession() {
        Object name = sessions.getSelectedItem(); return name == null ? null : new File(getFilesDir(), name.toString());
    }
    private void reloadSessions() {
        String[] names = getFilesDir().list((dir, name) -> name.startsWith("session-") && new File(dir, name).isDirectory());
        if (names == null) names = new String[0]; Arrays.sort(names, java.util.Collections.reverseOrder());
        sessions.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }
    private void refreshText() {
        if (state == null) return;
        state.setText(RecordingService.paused ? "In pausa (microfono aperto, campioni scartati)" : RecordingService.status);
        start.setEnabled(!RecordingService.active && !retrying); stop.setEnabled(RecordingService.recording);
        pause.setEnabled(RecordingService.recording); retry.setEnabled(!RecordingService.active && !retrying);
        export.setEnabled(!RecordingService.active && !retrying && selectedSession() != null);
        try { transcript.setText(Transcriber.transcript(selectedSession())); }
        catch (Exception e) { transcript.setText("Lettura sessione fallita: " + e.getMessage()); }
    }
    private void retry() {
        File session = selectedSession(); if (session == null || RecordingService.active || retrying || !credentials()) return;
        String apiKey = key.getText().toString().trim(), selectedModel = model.getText().toString().trim(), lang = language.getText().toString().trim();
        retrying = true; refreshText();
        worker.execute(() -> {
            try {
                // Process termination may leave an open WAV. Repair before requeueing.
                File[] partials = session.listFiles((dir, name) -> name.endsWith(".part"));
                if (partials != null) for (File file : partials) {
                    WavFile.repair(file);
                    if (!file.renameTo(new File(file.getPath().replace(".part", ".wav")))) throw new IOException("Recupero audio fallito");
                }
                for (File wav : Transcriber.segments(session)) Transcriber.transcribe(wav, apiKey, selectedModel, lang);
                RecordingService.status = "Sessione recuperata e trascritta";
            } catch (Exception e) { RecordingService.status = "Audio conservato · " + e.getMessage(); }
            finally { retrying = false; ui.post(this::refreshText); }
        });
    }
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20 || result != RESULT_OK || data == null || exportSession == null) return;
        File session = exportSession; Uri destination = data.getData();
        worker.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination); ZipOutputStream zip = new ZipOutputStream(out)) {
                for (File wav : Transcriber.segments(session)) { zip.putNextEntry(new ZipEntry(wav.getName())); Files.copy(wav.toPath(), zip); zip.closeEntry(); }
                zip.putNextEntry(new ZipEntry("trascrizione.txt")); zip.write(Transcriber.transcript(session).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                ui.post(() -> Toast.makeText(this, "Sessione esportata", Toast.LENGTH_LONG).show());
            } catch (Exception e) { ui.post(() -> Toast.makeText(this, "Export fallito: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }
    protected void onSaveInstanceState(Bundle saved) {
        // Do not persist the API key in Android's saved activity state.
        saved.putString("model", model.getText().toString()); saved.putString("language", language.getText().toString());
        super.onSaveInstanceState(saved);
    }
    protected void onResume() { super.onResume(); if (sessions != null) reloadSessions(); ui.post(refresh); }
    protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }
    protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
